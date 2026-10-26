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
