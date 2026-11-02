#!/usr/bin/env bash
# Build the official LibreOffice Android ARM64 core from a prepared core checkout.
# No placeholder or third-party native libraries are accepted.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LO_SRC="${1:-${LIBREOFFICE_SRC:-}}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
JOBS="${LO_BUILD_JOBS:-2}"
STAGE="$ROOT/native-engine/jniLibs/arm64-v8a"

fail() { echo "::error::$*" >&2; exit 2; }
[[ -n "$LO_SRC" && -d "$LO_SRC" ]] || fail "Pass a prepared LibreOffice core checkout as argument 1 or set LIBREOFFICE_SRC."
[[ -x "$LO_SRC/autogen.sh" ]] || fail "No autogen.sh in $LO_SRC; this is not a LibreOffice core source checkout."
[[ -n "$SDK" && -d "$SDK" ]] || fail "ANDROID_SDK_ROOT/ANDROID_HOME is missing."
[[ -n "$NDK" && -d "$NDK" ]] || fail "ANDROID_NDK_HOME/ANDROID_NDK_ROOT is missing."
[[ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]] || fail "Linux x86_64 Android NDK LLVM toolchain not found."
command -v make >/dev/null || fail "make is required."
command -v sha256sum >/dev/null || fail "sha256sum is required."
command -v readelf >/dev/null || command -v llvm-readelf >/dev/null || fail "readelf or llvm-readelf is required."

SDK="$(cd "$SDK" && pwd)"
NDK="$(cd "$NDK" && pwd)"
LO_SRC="$(cd "$LO_SRC" && pwd)"

# Use LibreOffice's dedicated AArch64 Android distro configuration.
# Do NOT combine --host=aarch64-linux-android with LibreOfficeAndroid:
# that 32-bit distro config appends --host=arm-linux-androideabi later,
# and Autoconf accepts the last --host value (silently selecting armv7a).
DISTRO_CONFIG="$LO_SRC/distro-configs/LibreOfficeAndroidAarch64.conf"
[[ -s "$DISTRO_CONFIG" ]] || fail "Missing $DISTRO_CONFIG. This LibreOffice ref does not provide the required AArch64 Android distro config."
if ! grep -Eq '^--host=aarch64-linux-android$' "$DISTRO_CONFIG"; then
  fail "LibreOfficeAndroidAarch64.conf does not declare --host=aarch64-linux-android; refusing an unverified distro config."
fi
if grep -Eq '^--host=(arm-linux-androideabi|arm-unknown-linux-androideabi)$' "$DISTRO_CONFIG"; then
  fail "LibreOfficeAndroidAarch64.conf contains a 32-bit ARM host override."
fi
cat > "$LO_SRC/autogen.input" <<AUTOGEN
--build=x86_64-unknown-linux-gnu
--with-distro=LibreOfficeAndroidAarch64
--with-android-sdk=$SDK
--with-android-ndk=$NDK
--disable-debug
--disable-dbgutil
AUTOGEN

printf 'LibreOffice source: %s\n' "$LO_SRC"
printf 'Android SDK: %s\nAndroid NDK: %s\nBuild jobs: %s\n' "$SDK" "$NDK" "$JOBS"
git -C "$LO_SRC" rev-parse HEAD | tee "$ROOT/native-engine/LIBREOFFICE-SOURCE-COMMIT.txt" || fail "LibreOffice source must be a Git checkout so its exact revision is recorded."

cd "$LO_SRC"
./autogen.sh

# Fail before the expensive C++ build unless configure really selected AArch64.
CONFIG_LOG="$LO_SRC/config.log"
[[ -s "$CONFIG_LOG" ]] || fail "configure did not produce config.log; cannot verify Android ABI."
if ! grep -Eq "host_cpu='aarch64'" "$CONFIG_LOG" || \
   ! grep -Eq 'Target: aarch64[^[:space:]]*android21' "$CONFIG_LOG"; then
  {
    echo "ERROR: LibreOffice configure did not select Android AArch64."
    echo "Expected host_cpu=aarch64 and an NDK compiler target like aarch64-unknown-linux-android21."
    echo "Detected configure summary:"
    grep -E "^(host=|host_alias=|host_cpu=|host_os=)" "$CONFIG_LOG" || true
    grep -E 'Target: .*android' "$CONFIG_LOG" | sort -u | tail -10 || true
  } | tee "$ROOT/native-engine/ARM64-ABI-PREFLIGHT-FAILED.txt" >&2
  fail "Refusing to spend time compiling a non-ARM64 LibreOffice core."
fi
rm -f "$ROOT/native-engine/ARM64-ABI-PREFLIGHT-FAILED.txt"
echo "ABI preflight passed: LibreOffice configure selected AArch64 Android."
make -j"$JOBS"

# The Android Gradle project may create several copies for intermediates and
# variants. Inspect every candidate and select only a genuine ELF64 AArch64
# library; never select by filename/count alone.
READELF="$(command -v llvm-readelf || command -v readelf)"
mapfile -t ALL_CORES < <(find "$LO_SRC/android" -type f -name liblo-native-code.so \
  -not -path '*/.gradle/*' -print 2>/dev/null | sort)
[[ ${#ALL_CORES[@]} -gt 0 ]] || fail "No liblo-native-code.so was produced under $LO_SRC/android."

REPORT="$ROOT/native-engine/ARM64-CORE-DISCOVERY.txt"
: > "$REPORT"
ARM64_CORES=()
for candidate in "${ALL_CORES[@]}"; do
  class="$("$READELF" -h "$candidate" 2>/dev/null | awk -F: '/Class:/{gsub(/^[ \t]+/,"",$2); print $2}')"
  machine="$("$READELF" -h "$candidate" 2>/dev/null | awk -F: '/Machine:/{gsub(/^[ \t]+/,"",$2); print $2}')"
  printf 'Candidate: %s\n  ELF class: %s\n  Machine: %s\n' "$candidate" "$class" "$machine" | tee -a "$REPORT"
  if [[ "$class" == ELF64 && "$machine" == *AArch64* ]]; then
    ARM64_CORES+=("$candidate")
    echo '  RESULT: ARM64' | tee -a "$REPORT"
  else
    echo '  RESULT: ignored (not ARM64 ELF64)' | tee -a "$REPORT"
  fi
done

[[ ${#ARM64_CORES[@]} -gt 0 ]] || fail "Found ${#ALL_CORES[@]} core candidate(s), but none is ELF64 AArch64. See $REPORT."

# Select the single final, symbol-stripped output for the exact variant built
# by this workflow. Gradle also keeps intermediate copies under
# merged_jni_libs, merged_native_libs, jniLibs and obj/local; those are NOT
# competing final outputs and must not make selection ambiguous.
FINAL_SUFFIX="/intermediates/stripped_native_libs/strippedUIDebug/stripStrippedUIDebugDebugSymbols/out/lib/arm64-v8a/liblo-native-code.so"
FINAL_CORES=()
for candidate in "${ARM64_CORES[@]}"; do
  [[ "$candidate" == *"$FINAL_SUFFIX" ]] && FINAL_CORES+=("$candidate")
done
if [[ ${#FINAL_CORES[@]} -ne 1 ]]; then
  {
    echo "Expected exactly one final strippedUIDebug ARM64 core; found ${#FINAL_CORES[@]}."
    echo "Expected path suffix: $FINAL_SUFFIX"
    echo "ARM64 candidates discovered:"
    printf '  %s\n' "${ARM64_CORES[@]}"
  } | tee -a "$REPORT" >&2
  fail "Cannot uniquely identify the final stripped ARM64 core. Refusing to select an intermediate copy."
fi
CORE="${FINAL_CORES[0]}"

OUT_DIR="$(dirname "$CORE")"
printf '\nSELECTED ARM64 CORE: %s\n' "$CORE" | tee -a "$REPORT"
printf 'Selected SHA-256: %s\n' "$(sha256sum "$CORE" | awk '{print $1}')" | tee -a "$REPORT"

mkdir -p "$STAGE"
find "$STAGE" -maxdepth 1 -type f -name '*.so' -delete
while IFS= read -r -d '' so; do
  cp -f "$so" "$STAGE/$(basename "$so")"
done < <(find "$OUT_DIR" -maxdepth 1 -type f -name '*.so' -print0)
[[ -s "$STAGE/liblo-native-code.so" ]] || fail "Core was not staged."

{
  echo "LibreOffice commit: $(git -C "$LO_SRC" rev-parse HEAD)"
  echo "Android SDK: $SDK"
  echo "Android NDK: $NDK"
  echo "Build jobs: $JOBS"
  echo "Output directory: $OUT_DIR"
  echo "ABI: arm64-v8a (ELF64 AArch64 verified)"
  echo "Discovery report: $REPORT"
  echo
  sha256sum "$STAGE"/*.so
} | tee "$ROOT/native-engine/ARM64-BUILD-MANIFEST.txt"

echo "Native core build and ARM64 staging completed. JNI bootstrap/runtime and the NiceExcel XLSX calculation adapter remain separate integration milestones."
