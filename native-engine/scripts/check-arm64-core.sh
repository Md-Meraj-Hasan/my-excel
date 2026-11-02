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

shopt -s nullglob
SO_FILES=("$LIB_DIR"/*.so)
if [[ ${#SO_FILES[@]} -eq 0 ]]; then
  echo "ERROR: No shared libraries found in $LIB_DIR" >&2
  exit 5
fi

echo "OK: Real ARM64 LibreOffice core found. Native libraries to package:"
for f in "${SO_FILES[@]}"; do
  printf ' - %s (%s bytes)\n' "$(basename "$f")" "$(stat -c %s "$f")"
done
sha256sum "${SO_FILES[@]}"
