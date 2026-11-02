# LibreOffice Android runtime build and verification

This folder contains the reproducible ARM64 LibreOffice runtime build/verification scripts and release runbook for NiceExcel.

## Pinned native build

- LibreOffice source: `6e0b39149b549bd05065d451006c4d060589891d`
- Android NDK: `27.2.12479018`
- Android API level selected by the LibreOffice configure: 21
- ABI: `arm64-v8a`
- Native shared libraries: exactly 14; see `../native/libreoffice/runtime-manifest.json`.

Run the manual GitHub Actions workflow **Build official LibreOffice Android ARM64 core**. It uses the dedicated `LibreOfficeAndroidAarch64` distro config and verifies the configure host is AArch64 before the expensive build. Do not replace it with a generic Android configure that can select ARMv7.

The workflow uploads two artifacts from the same run:
- `libreoffice-arm64-native-core`
- `libreoffice-arm64-runtime-assets`

## APK build

After the native workflow succeeds, **Build NiceExcel APK with LibreOffice ARM64** automatically downloads both artifacts from that exact run, merges `assets` and `assets_strippedUI`, stages the 14 .so files under `native-engine/jniLibs/arm64-v8a`, obtains the MPL-2.0 license text, runs Gradle/source/asset/ELF/hash checks, builds both app and instrumentation APKs, and audits the final APK.

The ordinary `build-apk.yml` workflow is tests-only. It intentionally does not publish an APK without the LibreOffice runtime.

## Real phone formula acceptance

The **LibreOffice Calc Real-Phone Formula Acceptance** workflow runs on a self-hosted Windows x64 runner labelled `niceexcel-android-device`, with one authorized ARM64 Android phone attached over USB. See `REAL-DEVICE-TESTING.md`.

A build or package audit is not a formula test. Release requires:
- `OK (2 tests)` from `LibreOfficeCalcIntegrationTest`;
- `CALC_COMMAND_SUCCESS` from the actual `.uno:CalculateHard` callback;
- `CALC_SAVE_REOPEN_OK` from a fresh LOK reopen;
- no POI/Kotlin fallback in the active Native formula path.

## Current status

The LOK Java/JNI bootstrap, isolated service, formula adapter, Native recalculation path, Save path, source/fixture CI, APK workflow, and physical-device workflow have been added to the integration branch. The ARM64 core and runtime asset artifacts were recovered from the older `Nice-Excel` repository and inspected; they are rebuilt within the target repository by the manual native workflow.

**Not yet proven:** the target repository has not yet produced a LibreOffice-enabled APK and no real phone calculation test has been run. Do not merge/release based only on source compilation or APK packaging.
