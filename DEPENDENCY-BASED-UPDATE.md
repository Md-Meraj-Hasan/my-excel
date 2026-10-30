# NiceExcel dependency-based formula refresh patch

## What changed
- Editing a cell now requests recalculation using that edited sheet/cell as the change source.
- The recalculation scheduler builds a dependency set from formula cell references, including cell ranges and sheet-qualified references.
- It recalculates the edited cell if it contains a formula, plus formulas that depend on it directly or through other formulas.
- Unrelated formula cells are excluded from the worker job and their cached values are retained.
- Initial workbook/sheet rendering still uses the existing full formula calculation path.

## Scope
Only `app/src/main/java/com/meraj/niceexcel/MainActivity.kt` was modified. Existing formula evaluation functions and UI code were not intentionally changed.

## Validation status
This archive is a source patch. The supplied repository ZIP does not include `gradlew` or the Gradle wrapper, and a local Android Gradle build was not run here. Run the project's GitHub Actions build before installing the APK.
