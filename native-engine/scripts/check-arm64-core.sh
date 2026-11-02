#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB_DIR="$ROOT/native-engine/jniLibs/arm64-v8a"
LIB="$LIB_DIR/liblo-native-code.so"

if [[ ! -s "$LIB" ]]; then
  echo "ERROR: LibreOffice ARM64 core is missing or empty: $LIB" >&2
  echo "Copy the REAL Android ARM64 LibreOffice build output into native-engine/jniLibs/arm64-v8a/." >&2
  exit 2
fi

READELF="$(command -v llvm-readelf || command -v readelf || true)"
if [[ -z "$READELF" ]]; then
  echo "ERROR: llvm-readelf/readelf is required." >&2
  exit 3
fi
CLASS=$("$READELF" -h "$LIB" | awk -F: '/Class:/{gsub(/^[ \t]+/,"",$2); print $2}')
MACHINE=$("$READELF" -h "$LIB" | awk -F: '/Machine:/{gsub(/^[ \t]+/,"",$2); print $2}')
TYPE=$("$READELF" -h "$LIB" | awk -F: '/Type:/{gsub(/^[ \t]+/,"",$2); print $2}')
if [[ "$CLASS" != "ELF64" || "$MACHINE" != *AArch64* || "$TYPE" != *DYN* ]]; then
  echo "ERROR: Expected Android ARM64 ELF64 shared object; got class='$CLASS' type='$TYPE' machine='$MACHINE'." >&2
  exit 4
fi

required=(
  libc++_shared.so libfreebl3.so liblo-native-code.so libnspr4.so
  libnss3.so libnssckbi.so libnssdbm3.so libnssutil3.so
  libplc4.so libplds4.so libsmime3.so libsoftokn3.so
  libsqlite3.so libssl3.so
)
for name in "${required[@]}"; do
  f="$LIB_DIR/$name"
  if [[ ! -s "$f" ]]; then
    echo "ERROR: Required matching LibreOffice ARM64 library is missing: $f" >&2
    exit 5
  fi
  file_class=$("$READELF" -h "$f" | awk -F: '/Class:/{gsub(/^[ \t]+/,"",$2); print $2}')
  file_machine=$("$READELF" -h "$f" | awk -F: '/Machine:/{gsub(/^[ \t]+/,"",$2); print $2}')
  if [[ "$file_class" != "ELF64" || "$file_machine" != *AArch64* ]]; then
    echo "ERROR: $name is not ELF64 AArch64 (class=$file_class machine=$file_machine)" >&2
    exit 6
  fi

  # Android 15+ devices may use 16 KB memory pages. Every PT_LOAD segment
  # in every bundled shared library must therefore have p_align >= 0x4000.
  while IFS= read -r align; do
    [[ "$align" =~ ^0x[0-9a-fA-F]+$ ]] || {
      echo "ERROR: Could not parse PT_LOAD alignment '$align' in $name" >&2
      exit 8
    }
    value=$((16#${align#0x}))
    if (( value < 16384 )); then
      echo "ERROR: $name has PT_LOAD alignment $align (< 0x4000). Rebuild with 16 KB ELF linker alignment." >&2
      exit 9
    fi
  done < <("$READELF" -lW "$f" | awk '$1 == "LOAD" { print $NF }')
done

shopt -s nullglob
SO_FILES=("$LIB_DIR"/*.so)
if [[ ${#SO_FILES[@]} -ne ${#required[@]} ]]; then
  echo "ERROR: Expected exactly ${#required[@]} ARM64 .so files; found ${#SO_FILES[@]}." >&2
  exit 7
fi

echo "OK: All ${#required[@]} LibreOffice ARM64 libraries are ELF64 AArch64 with 16 KB PT_LOAD alignment:"
for f in "${SO_FILES[@]}"; do
  printf ' - %s (%s bytes)\n' "$(basename "$f")" "$(stat -c %s "$f")"
done
sha256sum "${SO_FILES[@]}"
