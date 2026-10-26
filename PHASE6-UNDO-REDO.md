# Nice Excel Phase 6 — Undo/Redo and Edit Recovery

## Added
- Backend undo and redo endpoints: `POST /workbooks/{workbook_id}/undo` and `/redo`.
- A bounded history stack (up to 20 snapshots) for the active process.
- Each history restoration is persisted to the workbook recovery file.
- Undo/Redo controls added to the web spreadsheet toolbar.
- Android asset copy is kept in sync with the backend UI.
- Regression tests cover cell edit undo/redo and empty undo history.

## Scope and limitations
- Undo/redo history is in-memory and is cleared if the Python server process stops. The current workbook itself is autosaved to a private recovery XLSX file.
- History snapshots are currently workbook-level and can consume memory for large workbooks. The API limits each cell batch and range operations; consider delta-based history before very large production workbooks.
- Native Kotlin workbook state and Web Excel workbook state are still separate.
- This does not claim full Excel formula compatibility.

## Tests
Run from repository root: `PYTHONPATH=backend python -m pytest -q backend/tests`.
