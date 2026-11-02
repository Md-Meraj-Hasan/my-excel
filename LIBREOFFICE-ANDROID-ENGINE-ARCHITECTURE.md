# LibreOffice Calc engine architecture and verification plan

**Target:** NiceExcel Android Native app  
**Integration branch:** `feature/libreoffice-calc-runtime-blueprint`  
**Policy:** Do not call the integration complete until Calc has actually calculated and saved formulas on a physical ARM64 Android phone.

## 1. Connection methods considered

| Method | Connection | Advantages | Limitations | Decision |
|---|---|---|---|---|
| Official LibreOfficeKit + Android JNI | Kotlin/Java -> official LOK Java wrappers -> JNI -> bundled Calc core | Official Android architecture, offline, native ARM64 core, document load/save and UNO command dispatch | LOK is not the complete UNO Java object model; requires matching native libraries and runtime assets | **Selected** |
| Java UNO bridge | Kotlin/Java -> UNO Java API -> UNO bridge -> Calc | Direct interfaces such as `XCell`, `XCellRangeFormula`, `XCalculatable` | Needs a compatible Java UNO runtime/bridge and type registries; not a drop-in JAR and not present in the base app | Not first implementation |
| Custom C++ UNO/JNI bridge | Kotlin -> custom JNI -> C++ UNO interfaces -> Calc | Direct typed cell/range access and full control | Requires building and maintaining custom C++ glue against the exact LibreOffice ABI; more native failure surface | Only if LOK command/cache route proves insufficient |
| Headless `soffice --headless` process | Android process executes LibreOffice CLI | Familiar file-based recalculation | Android executable, process environment, runtime paths and process management are more fragile than official LOK integration | Not selected for Android |
| Remote LibreOffice server | Native UI -> network API -> server Calc | Easier server-side operations | Requires network/server; fails offline requirement | Rejected for Native APK |

### Important UNO distinction

The selected implementation invokes Calc's UNO command dispatcher through LOK's `Document.postUnoCommand()` using `.uno:CalculateHard` and waits for `CALLBACK_UNO_COMMAND_RESULT`. This runs the real Calc calculation engine.

It does **not** instantiate Java UNO interfaces such as `XCalculatable` or `XCell`. Instead, it saves the recalculated XLSX and reads Calc-written cached values from the saved OOXML workbook. If NiceExcel later needs direct per-cell UNO access, add a tested UNO bridge/native adapter as a separate phase; do not describe the current LOK command path as a full Java UNO bridge.

Official references:
- https://github.com/LibreOffice/core/blob/master/android/README.md
- https://docs.libreoffice.org/android.html
- https://docs.libreoffice.org/libreofficekit.html
- https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1sheet_1_1XCalculatable.html
- https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1table_1_1XCell.html

## 2. Repository baseline audit

The base commit audited was `041a6be8e987bd7c2ecdfde2288419065f170a63` on `main`.

Present at baseline:
- Native Kotlin grid and formula bar in `MainActivity.kt`.
- `XlsxCodec.kt` for XLSX OOXML read/write.
- Apache POI `PoiFormulaBridge.kt`.
- Existing Gradle/Chaquopy/Flask build and ordinary APK workflow.

Missing at baseline:
- `org.libreoffice.kit` Android JNI bootstrap Java classes.
- `liblo-native-code.so` and matching ARM64 dependency libraries.
- Generated LibreOffice `program/`, `share/`, and `unpack/` runtime assets.
- A Calc service/session lifecycle.
- Native formula acceptance instrumentation tests.
- APK native/asset checks and real-device formula workflow.

The current integration branch adds the LOK bootstrap, Calc adapter, isolated service, file-based IPC, runtime manifest, formula fixture, instrumentation tests, native build workflow, APK packaging checks and physical-device workflow. Keep these changes isolated from the stable `main` branch until the release gates pass.

## 3. Runtime architecture selected

```text
Existing NiceExcel Kotlin grid and formula bar
        |
        v
FormulaCalculationEngine (one production entry point)
        |
        v
LibreOfficeServiceClient -- private XLSX files + Binder paths
        |
        v
LibreOfficeEngineService (private :libreoffice process)
        |
        v
One serialized LibreOfficeKit worker thread
        |
        v
Official org.libreoffice.kit Java wrappers
        |
        v
liblo-native-code.so + matching ARM64 dependencies
        |
        v
LibreOfficeKit -> Calc document -> UNO command dispatch
        |
        | .uno:CalculateHard + completion callback
        v
Save XLSX -> close -> reopen -> read cached formula results
        |
        v
Return typed results -> update Native grid on main thread
```

The Calc renderer is not used to replace the NiceExcel grid. All native calls are serialized off the UI thread. A separate Android service process limits the impact of a native crash on the grid process.

## 4. Required native and runtime payload

All files must come from one pinned LibreOffice source revision and the same Android build.

### Native ARM64 libraries

The current runtime manifest records this candidate loader set:

```text
libnspr4.so
libplds4.so
libplc4.so
libnssutil3.so
libfreebl3.so
libsqlite3.so
libsoftokn3.so
libnss3.so
libnssckbi.so
libnssdbm3.so
libsmime3.so
libssl3.so
libc++_shared.so
liblo-native-code.so
```

The final authority is the ELF DT_NEEDED closure of the pinned build, not a hard-coded count. Verify every library as ELF64/AArch64, inspect dependencies, record SHA-256, and reject unresolved non-platform libraries. Do not use ARM64 libraries on armeabi-v7a or x86_64.

### Matching Java/JNI bootstrap

```text
app/src/main/java/org/libreoffice/kit/LibreOfficeKit.java
app/src/main/java/org/libreoffice/kit/Office.java
app/src/main/java/org/libreoffice/kit/Document.java
app/src/main/java/org/libreoffice/kit/DirectBufferAllocator.java
```

These wrappers must match the same LibreOffice source revision as the native core. Preserve MPL-2.0 notices. Do not hand-write JNI declarations to imitate the official wrappers.

### Runtime assets

Minimum preflight paths:

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
assets/licenses/MPL-2.0.txt
assets/licenses/THIRD-PARTY-LICENSES.md
```

The minimum list does not replace the full generated `program/`, `share/`, `unpack/`, registry, filter, configuration and font payload. Keep the whole generated asset tree from the matching build. The final APK audit must reject missing/empty assets and compressed runtime entries if the LOK APK-backed filesystem requires ZIP STORE entries.

## 5. Formula calculation and save lifecycle

1. Snapshot all sheets and formulas from the current workbook model.
2. Write a private temporary XLSX using NiceExcel's OOXML writer; pass only file paths over Binder.
3. In the isolated service, load the workbook with LOK and verify `DOCTYPE_SPREADSHEET`.
4. Register the LOK message callback, dispatch `.uno:CalculateHard`, and wait for the matching UNO command completion callback.
5. Save as `Calc MS Excel 2007 XML` to a different temporary file.
6. Destroy the document in `finally`; reopen the saved file in a new LOK document and verify spreadsheet type.
7. Read formula cached values and error types from the Calc-produced XLSX using POI as a **cache reader only**. Do not call POI FormulaEvaluator.
8. Return results keyed by `SheetName!A1`; update the Native grid on the main thread only if the workbook revision/job token still matches.
9. For Save, run the same Calc round-trip on the export file before copying it to the user's selected destination.

When the engine is unavailable or calculation fails, display an explicit `#ENGINE!`/save error. Never silently substitute the Kotlin, Python or POI evaluator. The production Native formula path must remain LibreOffice-only after device acceptance.

## 6. Automated verification pipeline

### A. Native core build
- Pin LibreOffice source commit, NDK, Android API level, build flags and build-job count.
- Build ARM64 core and generated runtime assets in one workflow run.
- Upload core, runtime assets, build manifest, source commit and diagnostics together.
- Verify ELF64/AArch64 and full dependency closure before upload.

### B. APK packaging
- Download artifacts from the exact native workflow run.
- Stage all native libraries and the full runtime asset tree.
- Verify the official bootstrap Java sources and license notices.
- Run JVM tests and compile Android instrumentation tests.
- Build APK, verify JNI library placement, runtime paths, ZIP compression, hashes and manifest.
- A green Gradle build is a packaging result only, not formula-engine success.

### C. Physical ARM64 phone acceptance
- Install both APKs on one authorized ARM64 Android phone connected by USB to the self-hosted runner.
- Require airplane mode for the offline test.
- Initialize the actual bundled LOK core.
- Open a real XLSX, dispatch hard Calc recalculation, save, close and reopen.
- Assert formula text, typed cached results and error values.
- Assert no POI/Python/Kotlin formula fallback is called.
- Require logcat evidence: `LOK_INITIALIZED`, `CALC_COMMAND_SUCCESS`, and `CALC_SAVE_REOPEN_OK`.
- Upload instrumentation output and logcat.

The self-hosted runner label is `niceexcel-android-device`; it must be registered on the Windows PC with Android SDK Platform Tools and exactly one authorized USB-connected ARM64 phone.

## 7. Deterministic formula acceptance cases

The fixture must verify at least:
- `=SUM(A1:A3)` -> 60
- `=AVERAGE(A1:A3)` -> 20
- `=COUNT(A1:A3)` -> 3
- `=VLOOKUP(102,Lookup!A2:B4,2,FALSE)` -> Orange
- `=INDEX(Lookup!B2:B4,MATCH(102,Lookup!A2:A4,0))` -> Orange
- `=XLOOKUP(102,Lookup!A2:A4,Lookup!B2:B4,"Not Found")` -> Orange
- Cross-sheet `SUM` -> 350.5
- Dependency mutation from 5 to 7 -> dependent results 14 and 15
- `=1/0`, `=SQRT(-1)`, `=VALUE("abc")`, invalid `INDIRECT` -> typed Calc errors, never zero/blank
- Save/close/reopen preserves formula text and cached values

For each case, assert the formula expression remains in the XLSX, the cached result has the correct type/value, and the Native UI receives the same value.

## 8. Current gate status

Status is intentionally split into separate proofs:

| Gate | Meaning |
|---|---|
| Source/compile gate | Fixture structure, JVM tests and instrumentation APK compile; does not execute Calc |
| Native build | Real ARM64 core and runtime asset artifacts built and verified |
| APK bundle audit | Correct native/runtime payload is packaged |
| Physical phone formula test | The actual phone loaded Calc, recalculated formulas and saved/reopened XLSX |

Only the final physical-phone gate proves the user's stated success condition. Do not merge or describe NiceExcel as LibreOffice-formula-engine-complete before that gate passes.
