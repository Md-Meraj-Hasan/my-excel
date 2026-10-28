# Phase 2 — Smart Table data layer and persistence

## Product priority

NiceExcel's primary value is data analysis in a well-designed table. Smart Table is therefore the first reporting feature: readable colored headers, alternating row fills, reliable column headers, Excel-native filter metadata, and a definition that survives save/open. Chart and dashboard rendering are not implemented in this phase.

## Implemented

- Added a workbook-embedded, `veryHidden` `_NiceExcelMeta` worksheet for versioned reporting metadata.
- JSON metadata is chunked across cells below Excel's 32,767-character per-cell limit.
- Web APIs read/write reporting metadata and create a Smart Table from a selected range.
- Smart Table validates rectangular ranges and header presence, normalizes blank/duplicate headers, applies eight color palettes, adds alternating row fills, and stores a table definition.
- Excel table objects include style and AutoFilter metadata. Optional totals row adds a SUBTOTAL row when the next row is empty.
- The web engine rehydrates Excel table objects from metadata if a lightweight editor saved the cells/metadata but did not serialize OOXML tableParts.
- Android `XlsxCodec` reads/writes the hidden metadata sheet without exposing it as a normal worksheet and preserves the reporting JSON through local XLSX save/open.
- Added tests for metadata validation, hidden-sheet persistence, large metadata chunking, table rehydration, and XLSX round trips.

## API endpoints

- `GET /workbooks/{book_id}/reporting/metadata`
- `PUT /workbooks/{book_id}/reporting/metadata` with `{ "metadata": { ... } }`
- `POST /workbooks/{book_id}/tables` with `sheet`, `source_range`, optional `name`, `table_id`, `style`, `filter_enabled`, and `total_row`.

## Known limitations / next work

- This phase adds the data/persistence layer; it does not yet add a Smart Table wizard to the ribbon.
- Native Android grid can preserve cell colors and reporting metadata, but the custom grid does not yet show per-column dropdown filter controls. That belongs in the next UI phase.
- If a workbook is saved by the Android lightweight codec, Excel table OOXML is re-created by the web engine when the workbook is next opened there. Direct Excel filter dropdown rendering in the Android custom grid is not part of this phase.
- Full application tests and APK build require the project CI/runtime dependencies; they were not available in this offline execution environment.
