# NiceExcel Cell Merge Fix V12

Changes based on the V11 review:
- Fixed malformed Kotlin string escaping in the XLSX `<mergeCells>` / `<mergeCell>` XML output.
- Restricted persisted merge ranges to valid ordered horizontal or vertical ranges.
- Kept merged-cell layout dimensions unchanged; visual expansion uses view transforms so neighboring row/column layout is not remeasured.
- Counter-scaled text glyphs so the merged background can expand without intentionally enlarging the font.
- Unmerge recreates the repeated anchor value in subordinate cells (the app only permits merging equal non-empty values) and copies the anchor style.
- On XLSX open, row count accounts for the last row referenced by merge ranges, including ranges whose subordinate cells contain no values.

Validation limitation: ZIP integrity and source-level checks were performed. An Android Gradle build/device test could not be run in this environment because the project archive has no Gradle wrapper and no system Gradle executable is installed. Do not treat this ZIP as device-verified until GitHub Actions builds it and the merge/save/reopen/unmerge flow is tested.
