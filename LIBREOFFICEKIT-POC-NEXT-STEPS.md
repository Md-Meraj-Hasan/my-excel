# LibreOfficeKit Android proof-of-concept — next stage

## Why not add a Maven dependency only?
LibreOfficeKit is a native C/C++ API that exposes LibreOffice functionality. The official Android application bundles a LibreOffice core native library and uses JNI. Its documented architecture uses separate UI, OpenGL rendering, LOKit and native LibreOffice threads; rendering and edit events must be handled by the client.

Official references:
- https://docs.libreoffice.org/android.html
- https://docs.libreoffice.org/libreofficekit.html
- https://github.com/LibreOffice/core/blob/master/android/README.md

## POC acceptance checklist
1. Build an Android ARM64 test APK with a compatible LibreOffice native core.
2. Open a small `.xlsx` file containing formulas, cell styles, multiple sheets, and a chart.
3. Edit a cell and formula, save, reopen in desktop LibreOffice, and compare results/styles.
4. Measure installed APK size, first-open time, memory, and scroll/edit latency on a real phone.
5. Only integrate into Nice Excel if build reproducibility, licensing notices, app size, and UI embedding are acceptable.

## Current status
This file is a technical plan only. LibreOfficeKit and its native binaries are **not integrated** in this project. The current app continues to use the existing Kotlin grid and lightweight XLSX codec.


## Current Nice Excel milestone
The Kotlin app now has a small additional text-formula evaluator for common text functions. It is not a substitute for LibreOffice's calculation engine. The Android LibreOfficeKit proof-of-concept remains separate and unintegrated.
