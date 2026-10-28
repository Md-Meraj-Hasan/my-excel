# NiceExcel Phase 1 — Reporting data model and audit

## Scope

Phase 1 establishes a versioned contract for Smart Table, Smart Chart and Dashboard metadata. It intentionally does not change the ribbon, render charts, mutate workbook files, or claim PDF/PNG export is implemented.

## Current-state findings from the supplied project ZIP

- The Flask API has an `/workbooks/{book_id}/charts` endpoint based on `openpyxl` chart objects.
- The current web Insert > Chart prompt exposes `bar`, `line` and `pie` only and asks for a range/title/anchor via browser prompts.
- The native `XlsxCodec` serializes worksheet cells/styles and does not currently preserve chart objects, Excel table definitions, hidden-sheet state, or a NiceExcel reporting metadata contract.
- Web workbook operations use `openpyxl`, while native workbook operations also use a lightweight Kotlin OOXML codec. Saving through both paths therefore needs explicit preservation tests before metadata is persisted in workbook packages.
- No dedicated persisted dashboard model was found in the inspected source.

## Contract

`niceexcel_api.reporting_metadata` is mirrored under both Python package roots:

- `backend/niceexcel_api/reporting_metadata.py`
- `app/src/main/python/niceexcel_api/reporting_metadata.py`

The version 1 JSON document has `schema_version`, `tables`, `charts`, and `dashboards` arrays. IDs are stable strings. Table and chart source ranges are stored separately from sheet names. Chart types are `bar`, `column`, `line`, `pie`, `donut`, and `combo`; charts default to linked source data. Dashboard components use stable IDs and non-negative layout geometry. Validation normalizes cell/range references and rejects unknown schema versions or invalid enums.

## Persistence decision for Phase 2

Do not rely on embedding `openpyxl` chart objects alone: the native codec does not preserve them. Before connecting UI features, choose and test one canonical persistence strategy. Candidate: versioned metadata stored in a dedicated hidden OOXML part/worksheet, with the native codec taught to preserve it. A sidecar JSON file is easier initially but needs lifecycle handling for rename, copy, import/export and Android storage. The decision should be made after a small cross-path round-trip prototype.

## Next phases

1. Prototype metadata persistence through web save/load and native codec read/write; add cross-path round-trip tests.
2. Implement Smart Table definitions and UI.
3. Implement editable linked charts for six requested types, including data-change refresh.
4. Implement dashboard component layout, filters and persistence.
5. Add fully offline PDF/PNG export and verify real outputs.

## Phase 1 validation

Run from `backend/` with project dependencies installed:

```sh
python -m pytest tests/test_reporting_metadata.py -q
```

The tests cover schema defaults, range normalization, six chart types, dashboard layout and invalid metadata rejection. They do not establish Android APK build success or workbook-package persistence.
