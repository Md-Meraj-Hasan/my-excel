# Nice Excel Flask backend

Offline-first local API prototype for Nice Excel. This backend is currently runnable on a desktop Python installation; it is **not yet bundled into the Android APK**.

## Run locally

```bash
python -m pip install -r requirements.txt
python -c 'from niceexcel_api.main import app; app.run(host="127.0.0.1", port=8765, debug=False, use_reloader=False, threaded=True)'
```

Open `http://127.0.0.1:8765/`. API documentation is available at `http://127.0.0.1:8765/docs`.

## Phase 4 additions

- Automatic recovery copies are written after workbook edits to `backend/data/recovery/`.
- `/recovery` lists available recovery workbooks.
- `POST /recovery/{workbook_id}/restore` restores a saved workbook into the in-memory session cache.
- Workbooks can be loaded lazily from recovery storage after the server process restarts, when the same backend data directory remains available.
- `POST /workbooks/{workbook_id}/charts` adds basic bar, line, or pie charts to a worksheet. Charts are stored in the XLSX and included in the downloaded workbook.
- Spreadsheet UI includes an **Insert chart** action.

## Important limitations

- This is a prototype. Recovery files are stored on the computer/server running Python; Android app-private storage integration is a later task.
- Formula calculation is a limited custom evaluator, not a complete Excel calculation engine. `openpyxl` preserves formula text but does not calculate Excel formulas itself.
- Undo/redo, fill handle, data validation, conditional formatting, and full Excel chart-editing controls are not implemented in this phase.
- For Android, Flask + Flask + Pydantic's native dependencies must be verified with the selected embedded Python runtime before claiming an APK-integrated build. This phase intentionally does not change the native Kotlin app or its Gradle dependencies.
- Keep the server bound to `127.0.0.1` for local use. Do not expose this prototype directly to a network without authentication and request security review.

## Tests

From this directory:

```bash
python -m pytest -q
```


## Phase 8 — Formula calculation and compatibility

- Added a **ƒx Calculate** action to evaluate supported formulas throughout every worksheet and report formula errors without replacing formula text.
- Added common formula functions: `COUNTBLANK`, `COUNTA`, `ABS`, `ROUND`, `ROUNDUP`, `ROUNDDOWN`, `INT`, `MOD`, `POWER`, `SQRT`, `LEN`, `UPPER`, `LOWER`, `PROPER`, `TRIM`, `LEFT`, `RIGHT`, `MID`, and `IFERROR`.
- XLSX export sets calculation mode to automatic and requests a full recalculation when opened in a spreadsheet program that supports the formulas.
- Formula compatibility remains intentionally partial. This is not a full Excel calculation engine; unsupported functions can return `#NAME?`, and this app's evaluator does not write calculated results into Excel's cached formula values.
- Validation: backend tests cover the new formulas and the calculate endpoint. Android Gradle/APK build still needs to be run separately.
