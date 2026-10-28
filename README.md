# Nice Excel v0.7.0

Native Kotlin Android spreadsheet project.

## Grid update
- New/blank worksheet grid starts with 50 rows and 20 columns (A–T).
- A green **+** button appears below the grid. Each tap adds exactly one row and scrolls to the new bottom.
- When opening an existing XLSX or CSV, the visible row count expands to at least 50 or the workbook/file's used row count.
- Existing workbook, formula, style, sort, filter, and GitHub Actions project files are retained.

## Build
GitHub Actions workflow: `.github/workflows/build-apk.yml`.

## Testing status
ZIP integrity is checked. Android Gradle compilation and on-device testing have not been run in this environment.


## v0.8.0 update
- Default worksheet grid now starts with 50 rows and 26 columns (A–Z).
- The existing add-row control remains: each tap adds one row.
- Existing workbook data can extend beyond the default grid based on imported content.
- No new runtime dependency added.

## Spreadsheet engine investigation
LibreOfficeKit is being evaluated as a possible future calculation/rendering engine. It is not bundled in this version: the official Android implementation requires native LibreOffice libraries, JNI integration, threaded rendering/input handling, and significant build/packaging work. This needs a separate proof-of-concept before replacing the current engine.


## v1.0.0 — text formula coverage
- Added text-returning formulas: `CONCAT`, `CONCATENATE`, `LEFT`, `RIGHT`, `MID`, `LEN`, `LOWER`, `UPPER`, `TRIM`, and `PROPER`.
- Improved `IF` formula display when the selected branch returns a quoted text literal or a text-valued cell.
- Text formulas are evaluated before numeric formula formatting so results such as `=IF(A1>0,"Yes","No")` can display text.
- Existing 50-row / 26-column grid, add-row control, formatting, XLSX/CSV workflow, and GitHub Actions retained.

Manual test cases (not yet run in Android):
- `=CONCAT("Nice"," Excel")` -> `Nice Excel`
- `=LEFT("Spreadsheet",6)` -> `Spread`
- `=RIGHT("Spreadsheet",6)` -> `sheet`
- `=MID("Spreadsheet",3,4)` -> `read`
- `=LEN("Excel")` -> `5`
- `=UPPER("nice")` -> `NICE`
- `=IF(1>0,"Yes","No")` -> `Yes`

Note: this is not full Microsoft Excel formula compatibility. Runtime/Android build tests are still required.


## v1.1.0 formula update
Added lightweight text formula support for `IFERROR`, `SUBSTITUTE`, `REPLACE`, `FIND`, `SEARCH`, and `TEXTJOIN` alongside the existing text functions. This is a custom, partial formula evaluator—not full Microsoft Excel compatibility. Formulas using unsupported syntax may show an error or a simplified result. Verify important workbooks in a full spreadsheet application.


## v1.2.0 formula regression tests
Added a JUnit 4 test suite for core text formulas, text extraction/transforms, replacement/search, TEXTJOIN over a range, IFERROR fallback, and basic numeric aggregate functions. `FormulaEngine` is now `internal` so tests in the same Kotlin module can exercise it. Run `./gradlew testDebugUnitTest` in a configured Android/Gradle environment. These tests are added but have not been executed in this environment; Android build and device compatibility remain unverified.


## v1.3.0 XLSX round-trip verification
Added an Android instrumentation round-trip test for multiple worksheets, plain text, numeric cells, formula strings, and common cell styling (bold, fill color, text color, font size, border, italic). GitHub Actions now runs `testDebugUnitTest` before building the APK. The instrumentation test itself requires a connected/emulated Android device and is not automatically run by this workflow; the actual codec test has not been executed in this environment. XLSX support remains partial and does not promise preservation of every Excel feature.


### v1.4.0 — used-range thick borders
- Table menu now applies thick borders to the used data rectangle from A1 through the last non-empty/formula cell. Blank cells inside that rectangle receive borders too.
- Thick borders are stored as medium OOXML cell borders in XLSX styles, and the reader restores the thick-border flag.
- This is cell formatting, not an Excel-native structured Table object.
- Android build/runtime tests have not been run in this workspace; use GitHub Actions to verify the APK build.

### v1.5.0 — imported formula evaluator (Priority 1–4 foundation)
- Added Apache POI's formula evaluator as an imported calculation engine and wired it to run before the previous lightweight evaluator.
- This is intended to expand coverage for standard Excel functions including conditional aggregates (`SUMIFS`, `COUNTIFS`, `AVERAGEIF/AVERAGEIFS` where supported), lookups (`VLOOKUP`, `HLOOKUP`, `INDEX`, `MATCH`), logical, date/time, text, math/statistical, and reference functions.
- Added JVM tests for `SUMIFS`, `VLOOKUP`, and `INDEX/MATCH`; these tests are added but have not been executed here.
- Important limitations: Apache POI does not implement every modern Microsoft 365 function, and some functions/syntax may differ by Excel version. Apache POI is primarily a Java desktop/server library, not an officially supported Android spreadsheet engine; APK dependency/build/runtime compatibility must be verified by GitHub Actions and on a real Android device. The existing evaluator remains as a fallback when POI cannot evaluate a formula. Do not treat the app as 100% Excel-compatible yet.

## v1.6.0 — Flask backend foundation (additive prototype)
- Added a separate `backend/` Flask module with workbook/session endpoints, XLSX open/save, cell range reads/writes, worksheet management, basic formatting, merge/freeze panes, find/replace, sorting and CSV import/export.
- Added backend unit tests and GitHub Actions steps to install Python 3.11 dependencies and run backend tests before the existing Android build.
- The existing Kotlin GUI and Android dependencies remain unchanged in this phase.
- Important: Flask is bundled into the APK. Android integration (Python runtime packaging, localhost lifecycle, WebView frontend, persistence and device testing) is the next phase after this backend API is validated.
- `openpyxl` does not calculate formulas; it preserves formula strings but a separate formula calculation engine is still required. Undo/redo, autosave/recovery, full formula compatibility, style-preserving sort, charts and conditional formatting are not implemented in this backend milestone.


## Phase 7 — session recovery and resume
- The Web spreadsheet remembers the most recently used workbook ID in WebView/browser local storage. On the next launch it attempts to reopen the autosaved private recovery copy.
- Added a Recover button to list recent recovery workbooks and explicitly restore one.
- Recovery files remain in the configured private recovery directory; no cloud service or internet connection is used.
- Session history for Undo/Redo remains in-memory and is not preserved across process restarts.

## Phase 9 — cross-sheet formula references and dependency recalculation
- The Flask formula evaluator now resolves same-sheet and cross-sheet cell references, including quoted sheet names such as `='Sales Data'!A1` and ranges such as `=SUM('Sales Data'!A1:A10)`.
- Cross-sheet references are supported in the existing limited formula subset, including range-based aggregates and VLOOKUP. Circular dependencies return `#CIRCULAR!` instead of recursing indefinitely.
- The web spreadsheet recalculates displayed formula results when its range is refreshed after an edit. Source formula strings remain intact in the workbook; this is not a full Excel calculation engine and does not guarantee every Excel formula or syntax.
- Synced the formula evaluator used by the Flask backend with the Android-bundled Python package.
- Validation in this workspace: backend tests and Python/JavaScript syntax checks; Android Gradle/APK build and on-device runtime remain unverified.


## Phase 10 — Formula Bar Live Preview
- Added a debounced formula preview while typing in the formula bar.
- Preview runs against the current workbook and sheet, shows supported results or formula errors, and does not commit changes or create undo history.
- Kept desktop Flask and Android Chaquopy Python packages and HTML assets in sync.
- This remains a limited formula subset, not full Excel compatibility.


## Phase 11 — Formula dependency graph

- Added `GET /workbooks/{workbook_id}/dependencies` to inspect formula nodes, their direct references, reverse dependents, and detected circular groups.
- Handles same-sheet and cross-sheet A1 references, quoted sheet names, absolute cell references, and bounded range expansion. Large ranges are summarized with corner references to avoid excessive work.
- Added a **Dependencies** UI button that displays a compact report.
- Backend and Android-bundled Python packages are kept in sync.
- This graph is diagnostic: it does not claim full Excel parsing or replace the formula evaluator. References inside string literals are ignored.

## Phase 12 — Incremental formula recalculation

- Cell updates now traverse the formula dependency graph and identify the edited formula cells plus their transitive dependents.
- Only affected formulas are passed to the supported formula evaluator; unrelated formulas are not included in the recalculation result.
- The cell-update response includes `recalculated_formulas` and `formula_errors` for the affected formula subset.
- Cross-sheet dependencies are supported by the same graph traversal.
- Formula text remains intact in workbook cells; calculated values are evaluated for display/API responses rather than written as cached XLSX values.
- Formula support remains a limited subset of Excel. Android APK/Gradle builds must still be verified in CI and on-device.


## Gradle Chaquopy plugin fix

The Chaquopy plugin version is declared centrally in the root `build.gradle` as `com.chaquo.python` version `16.1.0` with `apply false`. The app module applies `id 'com.chaquo.python'` without a version. Keep both files in sync when updating the repository.

## Smart Table UI (Phase 3)
See `PHASE3-SMART-TABLE-UI.md` for the Insert → Smart Table wizard, table styles, visible-grid filter strip, limitations, and verification notes.

### Phase 4 — Smart Table filtering and sorting
See `PHASE4-SMART-TABLE-FILTER-SORT.md` for multi-column AND filters, Smart Table sorting, row-insertion range updates, and current limitations.
