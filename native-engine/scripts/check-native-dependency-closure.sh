#!/usr/bin/env bash
# Validate that every non-platform DT_NEEDED dependency of the LibreOffice
# ARM64 shared objects is supplied by the same native build directory.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB_DIR="$ROOT/native-engine/jniLibs/arm64-v8a"
READELF="$(command -v llvm-readelf || command -v readelf || true)"
[[ -n "$READELF" ]] || { echo 'ERROR: llvm-readelf/readelf is required' >&2; exit 2; }
[[ -s "$LIB_DIR/liblo-native-code.so" ]] || { echo "ERROR: missing liblo-native-code.so in $LIB_DIR" >&2; exit 3; }
shopt -s nullglob
FILES=("$LIB_DIR"/*.so)
((${#FILES[@]} > 0)) || { echo 'ERROR: no .so files found' >&2; exit 4; }
# Android platform libraries are provided by the OS and must not be bundled.
PLATFORM='^(libc\.so|libm\.so|libdl\.so|liblog\.so|libandroid\.so|libEGL\.so|libGLESv[0-9]+\.so|libjnigraphics\.so|libz\.so|libOpenSLES\.so|libvulkan\.so|libmediandk\.so|libnativewindow\.so|libaaudio\.so)$'
missing=0
for so in "${FILES[@]}"; do
  while IFS= read -r dep; do
    [[ -z "$dep" ]] && continue
    if [[ "$dep" =~ $PLATFORM ]]; then
      continue
    fi
    if [[ ! -s "$LIB_DIR/$dep" ]]; then
      echo "MISSING: $(basename "$so") needs $dep, but it is not in $LIB_DIR" >&2
      missing=1
    fi
  done < <("$READELF" -d "$so" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done
if ((missing)); then
  echo 'ERROR: native dependency closure is incomplete. Use libraries from the SAME LibreOffice Android build.' >&2
  exit 5
fi
echo "OK: all non-platform DT_NEEDED entries resolve within $LIB_DIR"
