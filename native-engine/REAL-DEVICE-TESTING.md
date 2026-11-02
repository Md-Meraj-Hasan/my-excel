# NiceExcel LibreOffice real-phone acceptance

The physical-device workflow is intentionally separate from the APK build. A successful Gradle build or APK package audit is not formula-engine acceptance.

## One-time Windows PC setup

1. Install Android SDK Platform Tools and confirm `adb version` works.
2. On the Android phone, enable Developer options and USB debugging; connect it by USB and accept the computer authorization prompt.
3. Confirm exactly one phone is listed as `device` by running `adb devices -l`.
4. In GitHub, open **Settings → Actions → Runners → New self-hosted runner** for this repository. Download/configure the Windows x64 runner on the PC that has the USB-connected phone.
5. Add the custom runner label `niceexcel-android-device` and start the runner service. The runner must be online while the workflow runs.
6. The connected device must report `arm64-v8a` for `adb shell getprop ro.product.cpu.abi`.

Do not expose a self-hosted runner to untrusted pull-request workflows. The real-phone workflow is restricted to manual dispatch and successful APK build completion.

## Run order

1. Run **Build official LibreOffice Android ARM64 core** manually. Use the pinned source commit `6e0b39149b549bd05065d451006c4d060589891d` and NDK `27.2.12479018`.
2. On success, **Build NiceExcel APK with LibreOffice ARM64** starts automatically. It downloads the two artifacts from the exact native-build run, stages the runtime, verifies the APK, and uploads the app APK plus Android instrumentation APK.
3. When the self-hosted phone runner is online, **LibreOffice Calc Real-Phone Formula Acceptance** starts automatically. It installs both APKs and runs `LibreOfficeCalcIntegrationTest` on the connected phone.
4. The workflow must report `OK (2 tests)` and logcat must contain `CALC_COMMAND_SUCCESS` and `CALC_SAVE_REOPEN_OK`.

## Manual test from Android Studio / command line

With the LibreOffice-enabled APK installed and a connected ARM64 phone:

```bash
gradle --no-daemon connectedDebugAndroidTest
```

Or use the workflow above, which downloads the exact APKs from the completed build run and records the test output/logcat.

## What constitutes success

- Native LOK core initializes in the isolated `:libreoffice` process.
- Calc opens the generated XLSX as a spreadsheet.
- `.uno:CalculateHard` reports a successful completion callback.
- Basic aggregates, cross-sheet references, VLOOKUP, INDEX/MATCH, XLOOKUP and formula errors match the deterministic fixture.
- An edited input recalculates its dependents.
- The Calc-saved XLSX reopens in a fresh LOK document and retains formulas and cached results.
- No POI/Kotlin formula fallback is called.
- No native crash, ANR, or UI-thread calculation occurs.

If a test fails, download `NiceExcel-LibreOffice-real-phone-test-report` and inspect the instrumentation output and logcat before changing the Native grid.
