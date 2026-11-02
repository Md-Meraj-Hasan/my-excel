#!/usr/bin/env bash
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -s "$APK" ]]; then
  echo "ERROR: APK not found: $APK" >&2
  exit 2
fi
if ! command -v unzip >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: unzip and python3 are required." >&2
  exit 3
fi
ENTRY="lib/arm64-v8a/liblo-native-code.so"
if ! unzip -Z1 "$APK" | grep -Fxq "$ENTRY"; then
  echo "ERROR: APK was built without $ENTRY. Refusing to report a LibreOffice-enabled build." >&2
  echo "APK native entries:" >&2
  unzip -Z1 "$APK" | grep '^lib/' >&2 || true
  exit 4
fi
SIZE=$(unzip -p "$APK" "$ENTRY" | wc -c | tr -d ' ')
if [[ "$SIZE" -lt 1000000 ]]; then
  echo "ERROR: $ENTRY is unexpectedly small ($SIZE bytes); refusing likely placeholder/stub." >&2
  exit 5
fi

# LibreOffice's Android APK virtual filesystem (lo_apkentry) only reads STORE
# entries. AAPT compression silently makes registry/config payloads unreadable.
python3 - "$APK" <<'PY'
import sys, zipfile
apk = sys.argv[1]
required = (
    "assets/program/fundamentalrc",
    "assets/program/bootstraprc",
    "assets/program/unorc",
    "assets/program/services.rdb",
    "assets/unpack/program/sofficerc",
    "assets/unpack/program/udkapi.rdb",
    "assets/unpack/program/offapi.rdb",
    "assets/unpack/program/oovbaapi.rdb",
    "assets/unpack/etc/fonts/fonts.conf",
    "assets/share/registry/main.xcd",
    "assets/share/registry/res/registry_en-US.xcd",
)
required_libs = (
    "libc++_shared.so", "libfreebl3.so", "liblo-native-code.so",
    "libnspr4.so", "libnss3.so", "libnssckbi.so", "libnssdbm3.so",
    "libnssutil3.so", "libplc4.so", "libplds4.so", "libsmime3.so",
    "libsoftokn3.so", "libsqlite3.so", "libssl3.so",
)
with zipfile.ZipFile(apk) as z:
    names = set(z.namelist())
    missing = [name for name in required if name not in names or z.getinfo(name).file_size == 0]
    if missing:
        raise SystemExit("ERROR: Missing/empty LibreOffice APK assets: " + ", ".join(missing))
    missing_libs = [f"lib/arm64-v8a/{name}" for name in required_libs
                    if f"lib/arm64-v8a/{name}" not in names]
    if missing_libs:
        raise SystemExit("ERROR: Missing ARM64 native libraries: " + ", ".join(missing_libs))
    compressed = [i.filename for i in z.infolist()
                  if i.filename.startswith("assets/") and i.compress_type != zipfile.ZIP_STORED]
    if compressed:
        raise SystemExit("ERROR: LibreOffice APK assets must be uncompressed (ZIP STORE); first compressed entries: "
                         + ", ".join(compressed[:12]))
    print(f"OK: {len([n for n in names if n.startswith('assets/')])} LibreOffice APK assets are STORE/uncompressed.")
    print(f"OK: all {len(required_libs)} matching ARM64 native libraries are packaged.")
PY

echo "OK: APK contains $ENTRY ($SIZE bytes)."
unzip -Z1 "$APK" | grep '^lib/arm64-v8a/.*\.so$' | sort
