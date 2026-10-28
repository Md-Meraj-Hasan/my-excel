# Phase 3 — Smart Table UI

## What changed
- Added **Insert → Smart Table** to the compact ribbon in the web UI and Android WebView asset.
- Added a responsive modal wizard for source range, table name, 8 style palettes, header row, AutoFilter enablement, and optional totals row.
- The wizard uses the existing `POST /workbooks/{book_id}/tables` endpoint and reloads the persisted table metadata after creation.
- Added a Smart Table filter strip with column selection and Contains / Equals / Is not blank / Is blank conditions. Filters apply to visible worksheet rows inside the active table range and do not rewrite workbook values.
- Added a best-effort source-range guess based on the selected cell's contiguous data block; users can edit the range before creating the table.
- Kept web backend UI and Android `niceexcel_index.html` UI changes aligned.

## Important behavior/limits
- The grid shows 20 columns × 40 rows, so the on-screen filter only filters rows in that visible grid. Excel-native table AutoFilter is also written to the XLSX for use by compatible spreadsheet applications.
- If multiple Smart Tables exist on one sheet, the current filter strip targets the last table definition for that sheet. A table selector and multi-column filter combinations are follow-up work.
- Filter choices are session UI state; the table definition and style persist, but the current filter criteria are not yet persisted.
- This phase adds UI and calls the existing backend. It does not implement charts or dashboard UI.

## Validation
- JavaScript syntax checked with Node.js for both HTML assets.
- Reporting metadata/store regression tests: `14 passed`.
- Full application/API suite and Android APK build were not run in this environment because Flask is not installed here. Device rendering remains to be verified.
