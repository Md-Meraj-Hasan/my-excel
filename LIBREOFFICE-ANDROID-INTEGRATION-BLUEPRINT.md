# NiceExcel Android LibreOffice Calc integration blueprint

**Target repository:** `Md-Meraj-Hasan/my-excel`  
**Audited branch:** `main`  
**Audited commit:** `041a6be8e987bd7c2ecdfde2288419065f170a63`  
**Status:** Architecture and prerequisite audit only. No native engine is claimed to be integrated by this document.

## 1. Decision

Use the official LibreOffice Android core + LibreOfficeKit (LOK) Java/JNI bootstrap as the first implementation path. Keep NiceExcel's existing Kotlin grid and workbook model. Use LOK's UNO command dispatch to request a hard Calc recalculation, save the recalculated XLSX, then read the formula cached values from the saved OOXML package.

This is deliberately **not** a claim that LOK exposes the complete UNO Java object model. LOK is a C/C++ API; its Android integration uses LibreOffice's own JNI bootstrap. A full UNO Java bridge or a custom C++/UNO JNI API should be a later option only if the LOK command + XLSX cache round-trip cannot provide required functionality.

### Why this route

- It uses the same Android integration pattern as the official LibreOffice Android app.
- It avoids inventing a Java UNO runtime dependency that is not present in this project.
- It can calculate an XLSX workbook using the actual Calc core, then return cached formula values to NiceExcel's existing grid.
- It keeps LibreOffice's document renderer separate from NiceExcel's native grid.
- All LOK calls can be serialized on one worker thread and isolated from the UI process.

Official references:
- https://github.com/LibreOffice/core/blob/master/android/README.md
- https://docs.libreoffice.org/android.html
- https://docs.libreoffice.org/libreofficekit.html
- https://github.com/LibreOffice/core/blob/master/android/Bootstrap/src/org/libreoffice/kit/LibreOfficeKit.java

## 2. Current `my-excel` repository audit

At the audited commit, the following is present:

- `app/src/main/java/com/meraj/niceexcel/MainActivity.kt`: native Kotlin spreadsheet UI, formula display cache, dependency recalculation scheduling, and the existing formula selection path.
- `app/src/main/java/com/meraj/niceexcel/PoiFormulaBridge.kt`: Apache POI HSSF evaluator bridge.
- `app/src/main/java/com/meraj/niceexcel/XlsxCodec.kt`: custom XLSX OOXML import/export.
- `app/src/main/AndroidManifest.xml`: MainActivity and FlaskActivity.
- `app/build.gradle`: Apache POI `poi:5.4.1`, Chaquopy/Flask, and ABI filters for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- `.github/workflows/build-apk.yml`: backend tests, JVM tests, and APK packaging.

The following required LibreOffice integration pieces are **not present in this repository tree**:

| Required item | Current audit |
|---|---|
| `native-engine/jniLibs/arm64-v8a/liblo-native-code.so` | Missing |
| Matching dependent ARM64 `.so` files | Missing |
| Official `org.libreoffice.kit` Java bootstrap classes | Missing |
| LibreOffice runtime assets under `assets/program/` | Missing |
| Runtime assets under `assets/share/` | Missing |
| Runtime assets under `assets/unpack/` | Missing |
| LibreOffice-specific Gradle source-set / no-compress configuration | Missing |
| LOK initialization / document-load / save / destroy lifecycle | Missing |
| Calc formula acceptance instrumentation test | Missing |
| Device-side JNI/Calc formula verification workflow | Missing |

The current app's formula path is not a single engine: it has special-case local formula evaluation, then Apache POI, then a local fallback evaluator. This is visible in `MainActivity.kt`. The default-engine switch must replace this decision path centrally rather than adding another parallel evaluator call.

**Important artifact provenance:** ARM64 manifest/diagnostic files previously supplied in this project history refer to the separate repository `Md-Meraj-Hasan/Nice-Excel`, not this target repository `Md-Meraj-Hasan/my-excel`. They show a prior ARM64 core build and a 14-library set, but those binaries/assets must not be copied into this repository until their source commit, build configuration, hashes, JNI bootstrap, and runtime assets are proven to match. A same-named project is not proof of binary compatibility.

## 3. Recommended runtime architecture

```text
MainActivity / existing Native Kotlin grid
        |
        | workbook snapshot + formula edit/save request
        v
FormulaCalculationEngine (single production entry point)
        |
        v
LibreOfficeServiceClient
        |
        | Messenger / file paths (avoid large Binder payloads)
        v
LibreOfficeEngineService  [android:process=":libreoffice"]
        |
        | one dedicated single-thread executor; all LOK JNI calls serialized
        v
org.libreoffice.kit bootstrap
        |
        v
liblo-native-code.so + matching ARM64 dependencies
        |
        v
LibreOfficeKit -> Calc document model -> UNO command dispatch
        |
        | .uno:CalculateHard; save as Calc MS Excel 2007 XML
        v
Reopen saved XLSX -> read formula cached values + error types
        |
        v
return results -> update formulaDisplayCache on Main thread
```

### Formula request lifecycle

1. Snapshot all worksheets and formulas from the current workbook model. Do not send a large workbook map through Binder; write a private temporary XLSX and pass only its file path.
2. Open the temporary XLSX through LOK `Office.documentLoad(fileUri)`.
3. Verify the loaded document type is `DOCTYPE_SPREADSHEET`.
4. Dispatch `.uno:CalculateHard` through the LOK Document API. This is UNO command dispatch, not a direct call to Java `XCalculatable`.
5. Save to a separate temporary output file using the Calc XLSX filter (`Calc MS Excel 2007 XML`).
6. Destroy the loaded LOK document in a `finally` block.
7. Reopen the output through LOK and verify it is still a spreadsheet; destroy it.
8. Read formula strings, cached result values, and error cell types from the output OOXML package. The current `XlsxCodec` reads formula strings but does not expose cached formula values, so add a dedicated tested cache reader rather than pretending `XlsxCodec.read()` already returns them.
9. Return results keyed by `SheetName!A1`; update the existing formula cache and visible cells only on the Android main thread and only if the workbook revision has not changed.
10. For Save, run the same Calc round-trip on the actual temporary export before copying it to the selected destination.

Do not use the LibreOffice document renderer to replace the existing NiceExcel grid in this phase.

## 4. Native and runtime files required

All binary libraries, Java bootstrap classes, and runtime assets must come from the **same pinned LibreOffice source revision and Android build**.

### 4.1 Native libraries

Expected core library:

```text
native-engine/jniLibs/arm64-v8a/liblo-native-code.so
```

A previously supplied manifest from the separate `Nice-Excel` repository lists these 14 ARM64 shared libraries:

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

Treat this as a **candidate manifest only**, not as an unconditional allow-list. The authoritative list is the DT_NEEDED dependency closure of the actual pinned build. CI must inspect ELF class, machine, shared-object type, dependencies, SHA-256, and APK entries. Android platform libraries must not be copied into the app.

Start with `arm64-v8a` only. The current app's `armeabi-v7a` and `x86_64` ABI filters do not gain LibreOffice support just because the APK can still build for those ABIs. Either provide a separate non-LO product variant for them or fail clearly when the user tries to use the LO engine on an unsupported ABI. Do not load an ARM64 core on another ABI.

### 4.2 Official Android Java/JNI bootstrap

Vendor the matching upstream files, retaining their MPL-2.0 notices. At minimum inspect/copy the exact files required by the pinned source revision, including:

```text
app/src/main/java/org/libreoffice/kit/LibreOfficeKit.java
app/src/main/java/org/libreoffice/kit/Office.java
app/src/main/java/org/libreoffice/kit/Document.java
app/src/main/java/org/libreoffice/kit/DirectBufferAllocator.java
```

Do not hand-write JNI method declarations or mix Java wrappers from one LibreOffice commit with a native core from another. The upstream bootstrap loads the required dependent libraries in order and initializes native runtime paths from the app's data/cache/APK locations.

### 4.3 Runtime assets

Stage the generated Android runtime payload from the exact same LibreOffice build. The initial required-file gate should include (then expand from the actual generated manifest):

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

Also preserve the remaining generated `program/`, `share/`, `unpack/`, registry, filter, configuration, and font files. A short hand-picked list is only a minimum preflight, not proof that all Calc services are available.

The Android LOK bootstrap/runtime uses APK-backed assets and extracts the writable `unpack/` tree. Validate that all runtime assets are packaged in the format expected by the exact upstream Android bootstrap; the final APK check must reject missing, empty, or incorrectly compressed entries. Do not rely on Gradle's default asset compression without checking the final APK.

## 5. Kotlin integration boundaries

Introduce one interface, for example:

```kotlin
interface FormulaCalculationEngine {
    fun initialize(): EngineStatus
    fun recalculateWorkbook(inputXlsx: File, outputXlsx: File): CalculationResult
    fun shutdown()
}
```

Production implementation: `LibreOfficeCalcEngine`, backed by `LibreOfficeServiceClient` and the isolated service.

Responsibilities:
- Serialize every LOK call on one dedicated worker thread.
- Never call LOK from the Android UI thread.
- Use private app-cache files and atomic output replacement.
- Keep workbook revision/job tokens so stale results cannot overwrite newer edits.
- Keep a single loaded document lifecycle bounded by open/recalculate/save/destroy.
- Return structured values: numeric, text, boolean, blank, and formula error; do not coerce errors to zero.
- Expose engine state and diagnostic detail (library load, native init, document load, Calc command, save, reopen, cache parse).
- Close/destroy documents and the office handle during lifecycle shutdown where supported.

During migration, POI/local evaluation may remain behind an explicit development-only compatibility switch. Once the physical-device acceptance suite passes, production formula calculation must use LibreOffice as the default and must not silently fall back. If LO is unavailable, show an explicit engine error instead of displaying a result calculated by another engine.

## 6. Automatic verification design

### Gate A — source and bundle preflight (GitHub-hosted runner)

- Pin LibreOffice core commit, Android SDK/NDK versions, configure flags, and build scripts.
- Build the native core in a separate long-running job with adequate RAM/disk.
- Produce native-core and runtime-assets artifacts from the same workflow run.
- Save the source commit, toolchain versions, configure log, file manifest, and SHA-256 hashes.
- Verify every `.so` is ELF64/AArch64 and a shared object.
- Verify DT_NEEDED dependency closure.
- Verify required runtime assets exist and are non-empty.
- Verify APK contains the ARM64 core and all runtime assets with expected ZIP storage mode.
- Run JVM tests and Android compilation.

A green APK build or successful file-presence check is not formula-engine success.

### Gate B — instrumentation on ARM64 Android device

Run on a physical ARM64 phone connected to a self-hosted GitHub Actions runner (or a device-farm runner with equivalent real ARM64 Android support). An x86 emulator or a hosted Gradle build is not a substitute for the required phone acceptance test.

The instrumentation test must:
1. Assert supported ABI is ARM64.
2. Initialize the actual JNI bootstrap and report the loaded core/version.
3. Open a real XLSX fixture through LOK.
4. Recalculate through Calc.
5. Save, close, reopen, and inspect cached values.
6. Assert formulas remain formulas and errors remain errors.
7. Assert no POI/local fallback path was called.
8. Capture logcat and a machine-readable JSON result report.

### Formula acceptance fixture

Use a deterministic fixture with:

- `A1=10`, `A2=20`, `A3=30`, `B1=1+2` (formula in B1).
- `B2=SUM(A1:A3)` -> `60`.
- `B3=IF(A1>0,"YES","NO")` -> `YES`.
- Lookup table `Lookup!A2:B5` containing IDs 101, 105, 108, 110 and their labels; `=VLOOKUP(105,Lookup!A2:B5,2,FALSE)` -> expected label for 105.
- A second sheet with `=SUM('Sales Data'!B2:B4)` -> known deterministic total.
- `=CONCAT("Nice","Excel")` -> `NiceExcel`.
- `=1/0` -> a Calc formula error (must not become 0 or blank).
- A circular-reference fixture -> a Calc circular-reference error, not a hang/crash.

For every formula assert:
- formula expression is still present after save/reopen;
- cached result type and value are correct;
- formula error is preserved as an error;
- the same result is returned to the Native grid;
- repeated recalculation is deterministic.

### Gate C — UI stability and performance

On the same physical phone test:
- repeated cell edits and formula-bar edits;
- rapid sheet switching while calculation is pending;
- Back/keyboard dismissal during calculation;
- opening and saving multi-sheet workbooks;
- process death/relaunch recovery;
- memory, first initialization time, formula latency, and APK installed size.

## 7. CI workflow layout

Keep native compilation separate from the normal fast APK workflow:

1. `.github/workflows/build-libreoffice-arm64.yml`: pinned native core build; upload native and runtime artifacts together.
2. `.github/workflows/build-apk-with-libreoffice.yml`: download artifacts from the same native workflow run, stage them, run bundle checks, run tests, package APK.
3. `.github/workflows/verify-libreoffice-device.yml`: self-hosted ARM64 device runner; install APK, run instrumentation acceptance tests, collect logcat and JSON report.
4. Keep the existing `.github/workflows/build-apk.yml` for non-LO builds until the LO-enabled build and device gates pass. Do not make the ordinary APK workflow falsely claim Calc integration.

## 8. Go/no-go criteria

Do not switch the production engine default until all are true:

- Native core and all runtime assets are from one pinned source build.
- The APK contains the verified ARM64 core, dependent libraries, and required assets.
- JNI bootstrap initializes on a physical ARM64 Android phone.
- LOK opens an XLSX as a spreadsheet and successfully dispatches hard recalculation.
- Formula result values and formula errors pass the acceptance fixture after save/reopen.
- The Native UI displays the exact values returned from Calc.
- The save path persists formula strings and cached results.
- Repeated edit/recalculate/save does not crash, deadlock, or freeze the UI.
- CI produces a device-test report proving the above.

Until then, status must remain **POC / not production-integrated**.

## 9. Upstream API notes

- LibreOffice Android bundles the core in `liblo-native-code.so` and uses a dedicated LOK thread plus the native soffice thread: https://github.com/LibreOffice/core/blob/master/android/README.md
- LOK is a C/C++ API; its public documentation explicitly distinguishes it from direct UNO access: https://docs.libreoffice.org/libreofficekit.html
- Calc's UNO `XCalculatable` supports automatic calculation, `calculate()`, and `calculateAll()`: https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1sheet_1_1XCalculatable.html
- UNO `XCell` exposes formula, value, and error access: https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1table_1_1XCell.html
- UNO `XCellRangeFormula` and `XCellRangeData` provide batch formula/data access: https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1sheet_1_1XCellRangeFormula.html and https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1sheet_1_1XCellRangeData.html
