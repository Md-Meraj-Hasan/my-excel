#!/usr/bin/env python3
"""Strict packaging/source audit for NiceExcel's LibreOffice Android runtime.

This is deliberately a gate, not a fake formula test. It fails until a matching
LibreOffice Android core, bootstrap, JNI bridge and runtime payload are present.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

ABI_MACHINES = {
    "arm64-v8a": 183,   # EM_AARCH64
    "armeabi-v7a": 40,  # EM_ARM
    "x86_64": 62,       # EM_X86_64
}
BOOTSTRAP_MARKERS = (
    b"org/libreoffice/kit/LibreOfficeKit",
    b"LibreOfficeKit",
)
RUNTIME_PREFIXES = (
    "assets/program/",
    "assets/share/",
    "assets/lo-runtime/",
    "assets/libreoffice/",
)
LICENSE_MARKERS = ("license", "licence", "copying", "notice", "third-party")


def elf_machine(data: bytes) -> int | None:
    if len(data) < 20 or data[:4] != b"\x7fELF":
        return None
    elf_class = data[4]
    endian = data[5]
    if elf_class not in (1, 2) or endian not in (1, 2):
        return None
    byte_order = "<" if endian == 1 else ">"
    try:
        return struct.unpack(byte_order + "H", data[18:20])[0]
    except struct.error:
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, help="Built APK path")
    parser.add_argument("--source-root", default=".", help="Repository checkout")
    parser.add_argument("--abi", action="append", choices=sorted(ABI_MACHINES),
                        help="ABI to require (repeatable; defaults to arm64-v8a)")
    args = parser.parse_args()

    root = Path(args.source_root).resolve()
    apk = Path(args.apk).resolve()
    abis = args.abi or ["arm64-v8a"]
    report: dict[str, object] = {"apk": str(apk), "required_abis": abis, "checks": {}, "errors": []}
    errors: list[str] = report["errors"]  # type: ignore[assignment]

    source_requirements = {
        "JNI adapter C++": [
            root / "app/src/main/cpp",
            root / "app/src/main/jni",
        ],
        "CMake/native build definition": [
            root / "app/src/main/cpp/CMakeLists.txt",
            root / "app/src/main/CMakeLists.txt",
        ],
        "LibreOffice bootstrap source": [
            root / "app/src/main/java/org/libreoffice/kit/LibreOfficeKit.java",
            root / "app/src/main/java/org/libreoffice/kit/LibreOfficeKit.kt",
        ],
    }
    source_status = {}
    for label, candidates in source_requirements.items():
        found = any((p.is_dir() and any(p.rglob("*.cpp"))) or p.is_file() for p in candidates)
        source_status[label] = found
        if not found:
            errors.append(f"Missing source requirement: {label}")
    report["checks"]["source_files"] = source_status

    if not apk.is_file():
        errors.append(f"APK not found: {apk}")
        print(json.dumps(report, indent=2))
        return 1

    try:
        with zipfile.ZipFile(apk) as zf:
            names = zf.namelist()
            name_set = set(names)
            dex_names = [n for n in names if re.fullmatch(r"classes(?:\d+)?\.dex", n)]
            dex_blob = b"".join(zf.read(n) for n in dex_names)
            bootstrap_present = any(marker in dex_blob for marker in BOOTSTRAP_MARKERS)
            if not bootstrap_present:
                errors.append("Compiled DEX does not contain the LibreOfficeKit bootstrap marker")

            runtime_files = [n for n in names if n.startswith(RUNTIME_PREFIXES)]
            if not runtime_files:
                errors.append("No recognized LibreOffice runtime asset directory found in APK")

            license_files = [n for n in names if any(m in n.lower() for m in LICENSE_MARKERS)]
            if not license_files:
                errors.append("No bundled license/third-party notice path found in APK")

            abi_status = {}
            for abi in abis:
                core_path = f"lib/{abi}/liblo-native-code.so"
                if core_path not in name_set:
                    abi_status[abi] = {"core_present": False}
                    errors.append(f"Missing {core_path}")
                    continue
                data = zf.read(core_path)
                machine = elf_machine(data)
                expected = ABI_MACHINES[abi]
                ok = machine == expected
                abi_status[abi] = {
                    "core_present": True,
                    "size_bytes": len(data),
                    "elf_machine": machine,
                    "expected_elf_machine": expected,
                    "elf_arch_matches": ok,
                }
                if len(data) < 1024 * 1024:
                    errors.append(f"{core_path} is unexpectedly small/truncated ({len(data)} bytes)")
                if not ok:
                    errors.append(f"{core_path} ELF machine mismatch: got {machine}, expected {expected}")

            report["checks"]["bootstrap_dex_present"] = bootstrap_present
            report["checks"]["runtime_asset_file_count"] = len(runtime_files)
            report["checks"]["license_notice_file_count"] = len(license_files)
            report["checks"]["abis"] = abi_status

            readelf = shutil.which("readelf")
            dependency_report = {}
            if readelf:
                for abi in abis:
                    core_path = f"lib/{abi}/liblo-native-code.so"
                    if core_path not in name_set:
                        continue
                    temp = root / ".lo-core-audit.tmp.so"
                    temp.write_bytes(zf.read(core_path))
                    try:
                        proc = subprocess.run(
                            [readelf, "-d", str(temp)],
                            check=False, capture_output=True, text=True,
                        )
                        needed = re.findall(r"Shared library: \[(.*?)\]", proc.stdout)
                        dependency_report[abi] = {
                            "readelf_exit": proc.returncode,
                            "needed_libraries": needed,
                        }
                        if proc.returncode != 0:
                            errors.append(f"readelf failed for {core_path}: {proc.stderr.strip()}")
                        # All non-system DT_NEEDED entries must be packaged in the same ABI.
                        packaged = {Path(n).name for n in names if n.startswith(f"lib/{abi}/")}
                        system_allow = {
                            "libandroid.so", "libc.so", "libdl.so", "liblog.so",
                            "libm.so", "libz.so", "libEGL.so", "libGLESv2.so",
                            "libOpenSLES.so", "libjnigraphics.so",
                        }
                        missing = [lib for lib in needed if lib not in packaged and lib not in system_allow]
                        if missing:
                            errors.append(f"Unpackaged DT_NEEDED libraries for {abi}: {missing}")
                    finally:
                        temp.unlink(missing_ok=True)
            else:
                report["checks"]["elf_dependency_scan"] = "SKIPPED: readelf unavailable"
            if readelf:
                report["checks"]["elf_dependencies"] = dependency_report

    except zipfile.BadZipFile:
        errors.append("APK is not a valid ZIP archive")
    except Exception as exc:  # diagnostic tool must report, never hide the cause
        errors.append(f"Audit exception: {type(exc).__name__}: {exc}")

    report["result"] = "PASS" if not errors else "FAIL"
    print(json.dumps(report, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
