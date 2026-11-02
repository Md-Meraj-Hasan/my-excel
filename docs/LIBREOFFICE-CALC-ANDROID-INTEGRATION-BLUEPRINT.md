# NiceExcel — LibreOffice Calc Android Integration Blueprint

**Target repository:** `Md-Meraj-Hasan/my-excel`  
**Working branch:** `feature/libreoffice-calc-runtime-blueprint`  
**Reference implementation repository:** `Md-Meraj-Hasan/Nice-Excel`  
**Status:** Runtime artifacts recovered and verified; implementation port and real-device formula acceptance remain outstanding.

## 1. Final architecture decision

For the existing Native Kotlin grid, the best first integration is **official LibreOfficeKit (LOK) Java/JNI bootstrap + Calc UNO command dispatch + XLSX round-trip**. A custom C++ UNO JNI bridge is not needed for the initial calculation workflow.

Flow:

1. NiceExcel snapshots the current multi-sheet cell model to a temporary XLSX workbook using Apache POI's OOXML writer. POI writes formulas; it does not calculate them.
2. The snapshot file path is sent to an isolated Android service process (`:libreoffice`) using Messenger/Binder. The file stays in the same app sandbox; do not send a large workbook through a Bundle.
3. Inside that process, a single dedicated LOK thread loads the XLSX through official `LibreOfficeKit` / `Office.documentLoad()`.
4. The loaded document must be a spreadsheet (`DOCTYPE_SPREADSHEET`).
5. The adapter posts `.uno:CalculateHard` with `notifyWhenFinished=true`, listens for `CALLBACK_UNO_COMMAND_RESULT`, and waits for an explicit successful completion before saving.
6. The document is saved with the `Calc MS Excel 2007 XML` filter, destroyed, and the saved XLSX is opened again in a fresh LOK document.
7. Apache POI reads only the formula cached values from the Calc-produced XLSX. It is not used to evaluate formulas.
8. Kotlin applies the returned results to `formulaDisplayCache` using the workbook revision token. Rendering remains a pure cache lookup.
9. The real XLSX Save path must also pass the user's actual workbook through Calc before the final file is delivered.

This makes **LibreOffice Calc the actual calculation engine** while preserving NiceExcel's existing Native grid. It is much smaller and safer than embedding a second custom UNO bridge into LibreOffice's C++ build.

### Why LOK + UNO command dispatch is enough initially

- `Document.postUnoCommand()` is a supported LOK API. The `notifyWhenFinished` flag requests `LOK_CALLBACK_UNO_COMMAND_RESULT`, whose JSON payload includes the command name and success status.
- `.uno:CalculateHard` invokes Calc's full recalculation command.
- The saved XLSX formula caches are read back by POI. A POI read of cached results is not a POI formula evaluation.
- If later NiceExcel needs direct typed cell access without an XLSX round-trip, add a dedicated C++ UNO bridge using `XCalculatable`, `XCell`, `XCellRangeData`, etc. That is a later optimization, not a prerequisite for proving Calc calculation.

Official references:
- Android LOK bootstrap and threading: https://docs.libreoffice.org/android.html
- LOK API and `postUnoCommand`: https://docs.libreoffice.org/libreofficekit.html
- LOK C++ API: https://github.com/LibreOffice/core/blob/master/include/LibreOfficeKit/LibreOfficeKit.hxx
- UNO command completion callback: https://github.com/LibreOffice/core/blob/master/include/LibreOfficeKit/LibreOfficeKitEnums.h
- UNO Calc recalculation interface (optional direct bridge): https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1sheet_1_1XCalculatable.html
- UNO cell interface (optional direct bridge): https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1table_1_1XCell.html

## 2. Repository comparison and recovered implementation

Two repositories exist and must not be confused:

- Target for this work: https://github.com/Md-Meraj-Hasan/my-excel
- Older LibreOffice integration reference: https://github.com/Md-Meraj-Hasan/Nice-Excel

The target `my-excel/main` currently contains the Native Kotlin grid, `PoiFormulaBridge.kt`, `XlsxCodec.kt`, and its regular build workflow, but does not contain the LOK Java bootstrap, LOK engine adapter, isolated service, or LibreOffice packaging workflows.

The older `Nice-Excel` repository contains these reusable reference files:
- `app/src/main/java/org/libreoffice/kit/LibreOfficeKit.java`
- `app/src/main/java/org/libreoffice/kit/Office.java`
- `app/src/main/java/org/libreoffice/kit/Document.java`
- `app/src/main/java/org/libreoffice/kit/DirectBufferAllocator.java`
- `app/src/main/java/com/meraj/niceexcel/LibreOfficeCalcEngine.kt`
- `app/src/main/java/com/meraj/niceexcel/LibreOfficeEngineService.java`
- `app/src/main/java/com/meraj/niceexcel/LibreOfficeServiceClient.kt`
- `native-engine/scripts/build-libreoffice-android-arm64.sh`
- `native-engine/scripts/check-arm64-core.sh`
- `native-engine/scripts/check-native-dependency-closure.sh`
- `native-engine/scripts/check-apk-libreoffice.sh`
- `.github/workflows/build-libreoffice-arm64.yml`
- `.github/workflows/build-apk-with-libreoffice.yml`
- `.github/workflows/verify-libreoffice-apk.yml`

These files are a reference implementation, not yet a verified drop-in patch for `my-excel`. The existing target `MainActivity.kt`, manifest, Gradle configuration, and save path must be adapted rather than replaced wholesale.

## 3. Native-core artifact inspection — verified

Recovered the actual GitHub Actions artifacts from old repository workflow run **41280547094**:

- Native core artifact ID: `13076063462`, ZIP size 82,322,712 bytes.
- Runtime assets artifact ID: `13076337479`, ZIP size 6,491,634 bytes.
- Diagnostics artifact ID: `13076680937`.
- LibreOffice source commit: `6e0b39149b549bd05065d451006c4d060589891d`.
- Android NDK: `27.2.12479018`.
- Android API target used by LO configure: 21.
- ABI: `arm64-v8a`.

I downloaded and inspected both ZIPs in this session:
- Both ZIP archives pass CRC/integrity checks.
- Native archive contains exactly 14 shared libraries.
- Every one of the 14 libraries is ELF64, machine ID 183 (AArch64).
- `liblo-native-code.so` is 207,175,976 bytes.
- The total uncompressed size of the 14 native libraries is 215,690,096 bytes. This is a substantial installed-storage cost; the exact final APK size has not yet been measured.
- `readelf -d` dependency closure was checked for all 14 libraries. All non-platform dependencies resolve inside the same archive; no missing dependency was found.
- SHA-256 values for all 14 libraries are recorded in `native/libreoffice/runtime-manifest.json`.

### Exact 14 native libraries

```text
libc++_shared.so
libfreebl3.so
liblo-native-code.so
libnspr4.so
libnss3.so
libnssckbi.so
libnssdbm3.so
libnssutil3.so
libplc4.so
libplds4.so
libsmime3.so
libsoftokn3.so
libsqlite3.so
libssl3.so
```

The source manifest and binary hashes match the historical build manifest. This is a real native core, not a placeholder or a library-count-only check.

## 4. Runtime assets — recovered, with an APK packaging constraint

The runtime ZIP contains 78 entries (16,737,097 bytes uncompressed). The two `assets_strippedUI/share/registry` entries must be merged into the final app's `assets/share/registry` tree.

Required final APK asset paths:

```text
assets/program/fundamentalrc
assets/program/bootstraprc
assets/program/unorc
assets/program/services.rdb
assets/unpack/program/sofficerc
assets/unpack/program/udkapi.rdb
assets/unpack/program/offapi.rdb
assets/unpack/program/oovbaapi.rdb
assets/unpack/etc/fonts/fonts.conf
assets/share/registry/main.xcd
assets/share/registry/res/registry_en-US.xcd
```

All 11 required files were found in the recovered runtime artifact after applying the documented merge of `assets` and `assets_strippedUI`.

**Critical:** the runtime artifact ZIP itself compresses its entries; that is acceptable. However, LibreOffice's Android APK virtual filesystem reads its packaged asset entries as ZIP STORE/uncompressed. The final APK must therefore package every `assets/` file uncompressed. JNI libraries are configured with legacy compression so the downloadable APK is smaller and Android extracts the .so files during installation; this does not reduce installed storage. The Gradle configuration uses:

```groovy
android {
    androidResources {
        noCompress += ""
    }
}
```

The APK verification script must reject any compressed `assets/` entry. Merely finding the files in the source tree is not enough.

## 5. Existing implementation issues that must be corrected before porting

The older `Nice-Excel` reference code is useful but must not be copied unchanged:

1. **LOK thread affinity:** `LibreOfficeEngineService.handleRequest()` currently calls `LibreOfficeKit.init()` on the service executor, while `LibreOfficeCalcEngine` later creates/uses the LOK `Office` on its own `loThread`. Move all LOK initialization, handle creation, document load, command dispatch, save, document destroy and office shutdown onto one single LOK worker thread in the isolated process.
2. **Calculation completion race:** the reference calls `postUnoCommand(".uno:CalculateHard", "{}", false)` and immediately saves. Change to `notifyWhenFinished=true`; register `Document.MessageCallback`; wait for `CALLBACK_UNO_COMMAND_RESULT`; parse the JSON; require the matching command name and `success=true` before save.
3. **Service cleanup:** close the document in `finally`; close/destroy the Office handle on the same LOK thread when the service shuts down. Never leave native handles alive after unbind/process teardown.
4. **Fallback behavior:** the old reference falls back to POI/Kotlin when LibreOffice fails. The target requirement is Calc as the default engine. Once the runtime is enabled, a Calc initialization/calculation failure must be surfaced as an engine error; do not silently present POI/Kotlin results as if Calc produced them. Keep old evaluators only behind an explicit temporary diagnostic/developer switch until device acceptance is complete.
5. **MainActivity path:** the current target `my-excel` has a POI/Kotlin evaluation loop in `scheduleFormulaRecalculation()`. The older reference's MainActivity already calls the LOK service first, but then falls back. Port only the service-first batching idea and remove fallback from the production calculation path after the Calc tests pass.
6. **UI thread:** the target's `displayForGrid()` already reads `formulaDisplayCache` and shows a pending marker. Preserve that. The old `display()` helper still contains synchronous POI/local evaluation logic; it is unused in the current target grid call sites and should be made cache-only or removed so it cannot become an accidental alternate path.
7. **Concurrency:** the target has a four-worker background pool for general operations. Add a dedicated single-thread/coalescing formula queue. One workbook/document must never be called concurrently from multiple workers. Discard stale results using the existing job ID and workbook revision.
8. **Snapshot vs Save:** the temporary calculation snapshot may be generated from the in-memory sheet maps. The user's real XLSX Save path must instead send the original exported workbook through Calc, save to a separate temporary output, verify/reopen it, and only then copy it to the selected destination. Do not rebuild the user's full styled workbook from a value-only snapshot.
9. **Result completeness:** compare the number and addresses of formula cells expected with the formula result cache returned by Calc. Missing result cells are a hard engine failure, not a reason to fall back to another evaluator.
10. **XLSX formula caches:** POI may read cached formula results from Calc's saved XLSX. It must not call a POI evaluator in this path. Error values must remain typed errors, never become numeric zero or blank.

## 6. Target repository changes required

### Source files to port/adapt

- Official LOK bootstrap: `LibreOfficeKit.java`, `Office.java`, `Document.java`, `DirectBufferAllocator.java` from the same LibreOffice commit as the native core. Retain MPL-2.0 notices.
- Kotlin engine adapter: adapted `LibreOfficeCalcEngine.kt` with a single LOK worker, initialization on that worker, callback-based hard-recalc completion, save/reopen verification, cache extraction, and deterministic shutdown.
- Isolated process service: adapted `LibreOfficeEngineService.java`.
- File-based service client: adapted `LibreOfficeServiceClient.kt`; pass private file paths over Binder, not large serialized workbook maps.
- Register service in `AndroidManifest.xml` with `android:process=":libreoffice"` and `android:exported="false"`.
- In `app/build.gradle`: map `native-engine/jniLibs` as the app JNI source set; add `poi-ooxml:5.4.1` for snapshot writing/cache reading; set all LibreOffice assets to no-compress; add verification tasks.
- Keep the existing Native UI, keyboard/touch handling, grid, ribbon and sheet model.

### Native artifact and asset staging

The 207 MB native core must not be committed as a source file. Port the reference build workflow so that:
- the native build job checks out the exact LO commit and NDK;
- it builds ARM64 using the dedicated `LibreOfficeAndroidAarch64` distro config (not the 32-bit `LibreOfficeAndroid` config);
- it verifies configure selected `aarch64-linux-android` before the expensive build;
- it uploads the 14 matching native libraries and runtime assets from the same workflow run;
- the APK workflow downloads both artifacts from that exact run ID, stages them, and verifies the final APK.

The reference native build script explicitly guards against the previous failure where configure silently selected `armv7a` because a later `--host=arm-linux-androideabi` overrode the intended AArch64 host. Keep that guard.

## 7. MainActivity integration contract

Current target code:
- `scheduleFormulaRecalculation()` snapshots all sheets, identifies formula cells and affected dependents, then calls POI/Kotlin formula evaluators on a general worker.
- `displayForGrid()` is already cache-only and must remain so.
- Existing `formulaDisplayCache`, `formulaRecalcJobId`, and `workbookRevision` are useful and should be retained.

New flow:
1. Capture the workbook snapshot and expected formula address set.
2. Enqueue a single latest-wins formula job off the UI thread.
3. Build one temporary XLSX snapshot for the entire workbook.
4. Ask `LibreOfficeServiceClient` to recalculate it in the isolated service.
5. Require one returned cached result for every formula cell in the snapshot.
6. Normalize result types: number, text, boolean, and Excel error. Apply existing number formatting in Kotlin only to numeric display values.
7. Post results to the UI only if job ID and workbook revision still match.
8. On any engine failure, show `#ENGINE!`/a clear status and log the exact LO error. Do not show a POI/Kotlin value as a Calc result.
9. On Save, use the same LOK worker to recalculate the actual XLSX export and save a verified Calc-produced XLSX before committing it to the user's destination.

### Workbook-model limitation for this first bridge

The live calculation snapshot is currently generated from NiceExcel's in-memory cell maps using `XlsxCodec`. It contains the current sheets, values and formula strings, but it does not reconstruct workbook features that the current Native model/codec does not represent (for example some named ranges, structured tables, pivot tables, external links, charts or advanced metadata). The user's Save path also uses the existing `XlsxCodec`, whose documented scope is common cell styles rather than every Excel feature.

Therefore this milestone proves that LibreOffice Calc calculates formulas in the workbook model NiceExcel currently supports; it does not yet make LibreOffice the authoritative editor for every possible Excel workbook feature. A later compatibility phase should retain the original LOK document as the workbook source of truth and synchronize Native edits into that document through LOK/UNO commands, instead of rebuilding every calculation snapshot from maps.

## 8. Formula verification suite

Fixture: `testdata/libreoffice/formula-cases.json`. It defines Excel-English/A1 formula inputs and expected typed outputs.

Required test groups:
- Basic math and aggregates: `=1+2`, `SUM`, `AVERAGE`, `COUNT`.
- Lookup: exact `VLOOKUP`, `INDEX/MATCH`, `XLOOKUP` (record unsupported-version behavior explicitly).
- Cross-sheet formulas with quoted sheet names.
- Dependency recalculation after editing an input.
- Errors: `=1/0`, `=SQRT(-1)`, `=VALUE("abc")`, invalid reference.
- Text, boolean, date/time and number formatting.
- Save XLSX, close document, reopen a fresh LOK document, and compare formulas and cached results.
- Repeated open/calculate/save/close cycles and rapid cell edits.

UNO formula grammar note: if a future direct UNO bridge uses `XCell.setFormula()`, do not pass arbitrary locale-specific formula bar strings without parsing. Its API formula grammar is English/A1; use the UNO formula parser to translate rather than replacing commas, semicolons or sheet separators with string operations.

## 9. CI and device verification

### GitHub Actions package gate
The new `.github/workflows/libreoffice-runtime-gate.yml`:
- runs JVM unit tests and validates the formula fixture structure;
- builds the debug APK;
- checks all 14 ARM64 native libraries and their ELF machine IDs;
- checks the runtime asset paths, uncompressed ZIP STORE requirement and historical SHA-256 values;
- checks the bootstrap class is compiled into DEX;
- checks non-platform `DT_NEEDED` dependencies are packaged.

A passing package gate proves only that the matching runtime was packaged. It does not prove native initialization or formula correctness.

### Instrumentation and real phone acceptance
Add an Android instrumentation test that calls the actual isolated service and asserts the exact formula outputs from the fixture, including error types, cross-sheet references, dependency updates, and save/reopen. Run this test on a real ARM64 Android phone before release. Emulator tests are useful but do not replace physical-device acceptance.

Capture:
- device model, Android version and ABI;
- LO source commit and native library hashes;
- logcat lines for native initialization, document load, CalculateHard callback success, save and reopen;
- formula result comparison report;
- memory, elapsed time and crash/ANR status.

**Release GO criteria:** all package checks pass; the real phone initializes the bundled core; Calc reports successful hard recalculation; every expected formula result matches; save/reopen passes; no POI/Kotlin fallback is invoked; no UI-thread calculation or crash occurs.

## 10. Current status and next steps

Completed in this work branch:
- created the development branch and draft pull request;
- inspected the target `my-excel` formula call paths and compared the older `Nice-Excel` implementation;
- recovered and inspected the actual ARM64 native-core, runtime-assets and diagnostics artifacts from run 41280547094;
- verified all 14 native libraries are ELF64 AArch64, recorded SHA-256 values, and checked ELF dependency closure;
- recorded the pinned source commit, NDK, all 14 hashes and required runtime paths in `native/libreoffice/runtime-manifest.json`;
- ported the official LOK bootstrap wrapper classes, Calc engine adapter, isolated service and file-based client;
- routed Native formula recalculation through LibreOffice and made grid rendering cache-only;
- routed formula-containing XLSX Save operations through Calc before writing the selected destination;
- added formula fixture vectors, Android instrumentation tests, fixture validation, source compile gate, native build workflow, APK packaging workflow and real-phone acceptance workflow;
- passed GitHub Actions source gate: fixture validation, JVM unit tests and Android instrumentation APK compilation succeeded (run 41388628825);
- added strict checks for the native libraries, asset files, STORE compression, bootstrap DEX marker, binary hashes, service process and absence of POI/Kotlin fallback in the active Native formula path.

Not yet completed:
- run the native build workflow in the target repository and stage its artifacts;
- execute the native build workflow in the target repository and stage its artifacts;
- produce and inspect the target `my-excel` LibreOffice-enabled APK;
- run the two formula instrumentation tests on a real ARM64 Android phone;
- confirm the real phone logcat includes `CALC_COMMAND_SUCCESS` and `CALC_SAVE_REOPEN_OK`.

**Current status: IMPLEMENTED IN THE WORK BRANCH, NOT YET DEVICE-VERIFIED.** The Native calculation and Save paths now call the LOK service, but the target APK has not yet been built with the runtime and no real phone formula result has been observed.
