# NiceExcel — LibreOffice Calc Android Integration Blueprint

**Branch:** `feature/libreoffice-calc-runtime-blueprint`  
**Audited base:** `main` (repository tree and source files inspected before this branch)  
**Status:** Design and runtime contract only. No LibreOffice native runtime is currently integrated.

## 1. Executive decision

Target architecture for the existing Native Kotlin grid:

- Bundle a reproducible, ARM64-first LibreOffice Android core built from the official LibreOffice source.
- Use LibreOfficeKit (LOK) for document lifecycle, import/export, and optional rendering/event integration.
- Add a small JNI adapter compiled and linked into the LibreOffice Android native build. The adapter must use the Calc/UNO C++ document model to expose typed cell/formula operations and recalculation.
- Keep Kotlin as the UI and workbook-session owner. All native calls run on one serialized engine worker, never the Android main thread.
- Do not use a desktop UNO Java bridge JAR or a separate `soffice` process as a shortcut. The Android build uses a specially linked native core; a Maven dependency cannot supply it.
- Keep Apache POI, Python and the Kotlin evaluator out of the active calculation path after the native engine passes acceptance tests. Retain POI only for explicitly assigned non-calculation file utilities if needed.

**Important API boundary:** LOK and UNO are not interchangeable. LOK exposes a C/C++ document API and command interface; the public LOK documentation describes document conversion and experimental tiled rendering. The UNO Calc interfaces provide richer typed spreadsheet operations such as `XCalculatable.calculateAll()`, `XCell.getFormula()`, `XCell.getValue()`, and range operations. The proposed JNI adapter therefore needs a Calc/UNO C++ implementation inside the LibreOffice native build; do not assume LOK alone exposes every cell-level method required by NiceExcel.

Official references:
- https://docs.libreoffice.org/android.html
- https://docs.libreoffice.org/libreofficekit.html
- https://github.com/LibreOffice/core/blob/master/android/README.md
- https://github.com/LibreOffice/core/blob/master/README.cross
- https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1sheet_1_1XCalculatable.html
- https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1table_1_1XCell.html

## 2. Existing repository audit

Inspected current `main` source tree and these files:
- `app/build.gradle`
- `build.gradle`, `settings.gradle`, `gradle.properties`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/meraj/niceexcel/MainActivity.kt`
- `PoiFormulaBridge.kt`, `XlsxCodec.kt`
- Kotlin formula tests and `XlsxCodecRoundTripTest.kt`
- `.github/workflows/build-apk.yml`
- `LIBREOFFICEKIT-POC-NEXT-STEPS.md`

Findings:
1. `MainActivity.kt` owns the existing Native grid and currently references POI/fallback formula calculation paths and a formula display cache.
2. `PoiFormulaBridge.kt` creates an in-memory HSSF workbook to evaluate formulas; it is not LibreOffice.
3. `XlsxCodec.kt` is a lightweight OOXML codec and explicitly does not preserve every Excel feature. It stores formula strings but does not itself calculate them or write verified formula cached results.
4. `app/build.gradle` has Apache POI 5.4.1 and Chaquopy 16.1.0 / Python 3.11. The configured ABI filters currently include `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
5. The app uses AGP 8.9.2, Kotlin 2.1.10, Java 17, compileSdk 35; CI uses Gradle 8.11.1.
6. The tracked tree has no `app/src/main/cpp`, CMakeLists, `app/src/main/jniLibs`, `liblo-native-code.so`, official LOK bootstrap Java package, or LO runtime asset tree.
7. The tracked Android manifest requests INTERNET and cleartext traffic for the existing Flask/WebView feature. This is separate from the calculation engine; the Calc engine itself must work offline.
8. `LIBREOFFICEKIT-POC-NEXT-STEPS.md` already states that the native core is not integrated.

This is a source-tree audit, not an inspection of any untracked local files or the previously supplied diagnostic ZIPs. Reconcile those archives against the file manifest before selecting binaries.

## 3. Architecture and call flow

```text
Native Kotlin grid / formula bar
        |
        v
LibreOfficeCalcEngine (Kotlin facade; explicit engine state)
        |
        v
Single-threaded CalcEngineExecutor (serialized commands)
        |
        v
JNI adapter (small stable API; no UI objects cross JNI)
        |
        v
LibreOffice Android native core: liblo-native-code.so
  - LOK document lifecycle / import-export
  - Calc document model and UNO C++ interfaces
  - Calc formula parser, dependency graph, recalculation
        |
        v
Workbook document in private app storage
```

The native adapter should expose a narrow versioned contract, not leak UNO pointers into Kotlin:

- `nativeGetVersion()`
- `nativeInitialize(appDataDir, cacheDir, apkPath, assetManager)`
- `nativeOpenWorkbook(inputPath, readOnly)` -> document handle
- `nativeGetSheetNames(handle)`
- `nativeSetCellFormula(handle, sheet, address, formula)`
- `nativeSetCellValue(handle, sheet, address, typedValue)`
- `nativeGetCellSnapshot(handle, sheet, address)` -> formula, value type, value, display text, error code
- `nativeSetCellRange(...)` / `nativeGetCellRange(...)` for batch operations
- `nativeCalculate(handle, mode)` -> calculation status and changed-cell summary
- `nativeSaveAs(handle, outputPath, format)`
- `nativeCloseWorkbook(handle)`
- `nativeShutdown()`

The exact JNI signatures must be fixed in a versioned interface header and mirrored in Kotlin. All calls must return structured status/error values; no swallowed exceptions or fallback-to-POI behavior.

## 4. Required native source and runtime inventory

### A. Source/build inputs (must be pinned)
- Official LibreOffice core source commit/tag and its license notices.
- LibreOffice Android bootstrap sources from `android/Bootstrap` (including LOK Java/JNI bootstrap classes and supporting event/thread classes actually used).
- LibreOffice Android build configuration and `solenv/bin/native-code.py` registration for the required services.
- JNI adapter C++ source and public header owned by NiceExcel.
- NDK, SDK, CMake/build-system versions matching the selected LibreOffice source revision.
- Reproducible build script, checksums, build logs and SBOM/license inventory.

### B. ARM64 APK payload (must be proven from a real built artifact)
- `lib/arm64-v8a/liblo-native-code.so` — mandatory core; not interchangeable with generic desktop LibreOffice .so files.
- Any separately loaded Android dependencies required by the selected official build (for example the exact NSPR/NSS and C++ runtime libraries indicated by that build's loader). Do not copy a library list from another LibreOffice version without checking ELF DT_NEEDED and the official loader.
- LibreOffice runtime assets/configuration required by that exact build, including its generated program/registry/configuration/resource payload.
- Bootstrap classes and native method declarations compiled into DEX.
- MPL-2.0 and third-party notices, plus source/build provenance.
- Fonts, filters, dictionaries or other optional resources only when required by the selected Calc functionality; record each as required/optional.

**Do not** package the 14 .so / 79 runtime files from an earlier diagnostic ZIP until their source revision, ABI, ELF dependencies, checksums, licensing, and matching bootstrap/runtime layout are verified. File count alone is not evidence of a working runtime.

### C. ABI policy
Start with `arm64-v8a` only. Do not advertise `armeabi-v7a` or `x86_64` until each has its own matching native core and passes the same device tests. A Gradle ABI filter does not create a native binary.

### D. Android bootstrap requirements
Follow the official Android bootstrap flow for the selected LO revision: load the correct native dependencies in the required order, initialize native code with app data/cache/APK paths and AssetManager, prepare the LO profile/runtime directories, then create/open the LOK/Calc document on the engine worker. Verify every JNI symbol and required UNO service at runtime. Treat missing services as a hard initialization failure.

## 5. Engine ownership and default-engine rules

Introduce an explicit state machine:

`UNAVAILABLE -> INITIALIZING -> READY -> DOCUMENT_OPEN -> CALCULATING -> DOCUMENT_OPEN`

Any unrecoverable native error transitions to `FAILED`. The UI must show an engine error and must not silently calculate with POI, Python or Kotlin.

Default-engine enforcement:
1. Native engine readiness is required before formula calculation or workbook editing that requires recalculation.
2. Formula writes go to the Calc document first; display values are read back from Calc.
3. Automatic calculation is enabled; explicit full recalculation invokes UNO `XCalculatable.calculateAll()`.
4. Save waits for pending edits and calculation, then saves through the LO document/filter path.
5. Reopen the saved file in a fresh engine document and verify formulas and results.
6. Formula errors are returned as typed error values, not converted to zero or empty strings.
7. Batch edits are applied in order on the one engine worker; UI updates are posted to main thread only after completion.
8. No native document pointer is used after close; every open handle has deterministic cleanup.

## 6. Workbook bridge strategy

Do not make the lightweight `XlsxCodec` the authoritative save path for workbooks calculated by LibreOffice. Its current implementation writes formula XML without verified cached calculation results and supports only a subset of OOXML features.

Preferred flow:
- Import original XLSX into the LO Calc document.
- Keep the LO document as the authoritative live workbook during the session.
- Read/write cell values and formulas via the native adapter.
- Save/export with LibreOffice's XLSX filter after recalculation.
- Reopen the exported XLSX with a fresh LO document and validate results.

The existing codec may remain for CSV/simple formats or explicitly limited fallback utilities, but not as a silent post-save rewrite of an LO-calculated workbook.

## 7. Automated verification: required test layers

### Layer 1 — Build/package gate (every native-runtime PR)
- Build APK for ARM64.
- Inspect APK ZIP contents; require the expected core library, bootstrap classes, runtime assets and license notices.
- Parse ELF headers: require AArch64 machine type for ARM64 core.
- Inspect dynamic dependencies and fail for unresolved/missing packaged dependencies.
- Verify the app's runtime manifest version matches the JNI adapter's expected ABI/API version.
- Reject empty/truncated native libraries and unexpected duplicate ABI payloads.

### Layer 2 — Device instrumentation gate (emulator and physical ARM64)
Must run on a real ARM64 Android phone before release. Emulator is useful but is not a substitute for physical-device acceptance.

Instrumentation must initialize the actual bundled core, load a workbook, execute formulas, save, close, reopen, and re-read results. The test must assert the engine identity is LibreOffice and assert no POI/Python fallback invocation occurred.

### Layer 3 — Formula correctness suite
Use a deterministic workbook with these sheets and expected results:
- `Basic`: `=1+2` -> 3; `=SUM(A1:A3)`; `=AVERAGE(A1:A3)`; `=COUNT(A1:A3)`
- `Lookup`: exact `VLOOKUP`, `INDEX/MATCH`, `XLOOKUP` where supported by selected Calc version; test missing key returns the expected not-found value/error.
- `CrossSheet`: `=SUM('Sales Data'.B2:B4)` and quoted sheet names.
- `Dependencies`: chained formulas; edit an input and verify dependent cells update.
- `Errors`: `=1/0`, `=SQRT(-1)`, `=VALUE("abc")`, invalid reference; assert typed errors, never zero/blank.
- `DatesText`: date/time, locale-sensitive separators, text functions and formatting.
- `Cycles`: circular reference; assert controlled Calc error/no hang.
- `SaveReopen`: verify formula strings, cached/display values, sheet names and representative styles after export and fresh reopen.

Compare numeric results with tolerance; compare text, booleans and error codes exactly. Record Calc version, device model, Android version, ABI, elapsed time, peak memory, and logcat.

### Layer 4 — Stress/lifecycle
- 500 sequential cell edits with formula dependents.
- 100 repeated open/recalculate/save/close cycles.
- background/foreground during calculation.
- Back navigation during edit/IME dismissal.
- low-memory/process recreation behavior.
- no UI-thread blocking, native crash, leaked document handles, or stale result commits.

## 8. Current missing-file matrix

| Required item | Current repository audit |
|---|---|
| `liblo-native-code.so` ARM64 | Missing |
| Matching LO native dependency set | Missing / unverified |
| Official Android LOK bootstrap Java sources | Missing |
| NiceExcel JNI adapter C++ source | Missing |
| CMake/NDK native build integration | Missing |
| LO runtime assets / registry payload | Missing |
| Native service registration manifest | Missing |
| Runtime/API version contract | Missing |
| Real Calc instrumentation tests | Missing |
| Formula verification workbook / fixture | Not found in tracked app test assets |
| License/provenance/SBOM for bundled LO core | Missing |

## 10. Exact MainActivity migration points discovered

A full fetch of the current 3,351-line `MainActivity.kt` confirms two active calculation paths, not one:

- `scheduleFormulaRecalculation()` around lines 1,660–1,710: snapshots workbook maps, evaluates formulas on the background executor, currently tries local special cases, then `PoiFormulaBridge.evaluate(...)`, then `FormulaEngine`, and commits results into `formulaDisplayCache`.
- `display(address)` around lines 2,735–2,780: synchronously tries local formula evaluation, then `PoiFormulaBridge.evaluate(sheets, sheetName, address, expression)`, then Kotlin `FormulaEngine`. This can be reached from rendering and must not become a synchronous JNI/Calc call.
- `formulaDisplayCache` is invalidated at many mutation points and currently keys rendered values by sheet/address/formula/number format.

Required migration:
1. Introduce one workbook-scoped Calc document session; do not create a temporary Calc document for each formula cell.
2. On import/open, create the LO document and hydrate the existing UI's cell snapshot from the authoritative Calc workbook.
3. On cell edit/paste/fill/sort/sheet operation, enqueue a revision-tagged batch update to the single Calc worker.
4. Recalculate once per committed batch (dirty calculation by default; explicit full calculation for open/refresh/verification).
5. Return a revision-tagged batch of formula result snapshots; discard stale responses if the UI workbook revision has advanced.
6. Replace the POI/Kotlin evaluator branch in `scheduleFormulaRecalculation()` with the engine service.
7. Change `display(address)` to a pure cache lookup for formula cells (show a pending marker while a Calc result is pending). It must never call JNI or calculate formulas.
8. Keep number-format rendering in Kotlin only if it does not alter the stored Calc value; otherwise request formatted display text from Calc.
9. Save/export from the LO document, not by rewriting it through the current limited `XlsxCodec`.
10. Add a guard test that fails if `PoiFormulaBridge.evaluate` or `FormulaEngine.eval` is reached from the Native UI's active production formula path after migration.

The existing `backgroundExecutor` has four workers. LibreOffice document handles must not be concurrently accessed by four workers: use a dedicated single-thread executor for every call into one LO document, with explicit queue/back-pressure and revision cancellation. The existing general executor can continue to handle independent non-LO operations.

## 11. Implementation sequence and stop/go criteria

**Milestone A — provenance and binary audit**
- Obtain the user's earlier native-core and diagnostics archives or locate their exact repository artifacts.
- Match binary build revision, ABI, checksums, ELF dependencies, runtime asset layout and licenses.
- If provenance cannot be proven, rebuild from official source; do not mix binaries from different builds.

**Milestone B — standalone ARM64 native POC**
- Build a minimal Android test target using official bootstrap and the same native core.
- Open a generated XLSX with formulas.
- Set a formula, calculate through Calc, read typed result, save, close and reopen.
- Capture logcat and native initialization/service diagnostics.

**Milestone C — NiceExcel adapter**
- Add JNI adapter and Kotlin facade.
- Add formula/device instrumentation tests.
- Keep current UI untouched; wire a feature-gated internal test path first.

**Milestone D — default engine migration**
- Route all native grid formula operations through Calc.
- Disable old calculation paths only after all acceptance tests pass.
- Preserve the existing Native UI, edit/keyboard handling and workbook workflows.

**Release GO criteria:** all package checks pass; real ARM64 phone initializes the bundled LibreOffice core; all formula correctness and save/reopen tests pass; no fallback engine is used; no crash or UI-thread calculation; licensing/provenance and APK size are reviewed.

**Current status: NO-GO for claiming integration.** The repository currently contains no LibreOffice runtime. This blueprint is not evidence that formulas calculate through Calc.
