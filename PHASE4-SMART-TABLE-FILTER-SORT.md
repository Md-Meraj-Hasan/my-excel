# Phase 4 — Smart Table filtering, sorting and row-range maintenance

## Included
- Smart Table conditions can now be added/updated per column. Multiple active conditions are combined with AND; selecting a column again updates that column's condition.
- Supported conditions: Contains, Equals, Is not blank, Is blank.
- Clear all removes Smart Table conditions. Existing general grid filter remains separate.
- Sort A→Z / Z→A now sorts data rows inside the active Smart Table when the selected cell is in its columns, keeping the header out of the sort range. Without an active table, the existing manual range prompt remains.
- Inserting rows above a table shifts its metadata range; inserting within the table or immediately after its range expands the range. `data_range` and the in-memory native XLSX table reference are updated where available.
- Both web UI and Android asset copies are updated together.

## Limitations
- Filters affect rows rendered in the app's current visible grid (20 columns × 40 rows); they do not implement full-workbook server-side filtering.
- The UI still targets the last Smart Table on the active sheet if multiple tables exist.
- Row insertion expands table ranges but does not automatically copy formulas/styles into the newly inserted blank row, nor reconstruct a totals formula if the insertion changes a totals-row position.
- Full API test suite and Android APK build were not run in this environment.
