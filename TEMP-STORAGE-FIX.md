# Nice Excel temporary storage behavior

- Android runtime temporary files are directed to the app-private `filesDir/niceexcel/data/temp` directory.
- Python `tempfile`, `TMPDIR`, `TMP`, and `TEMP` are configured to use that local directory before the API is imported.
- Workbook uploads are copied to a unique local-disk temporary `.xlsx`/`.xlsm` file in 1 MiB chunks, with the existing 50 MiB limit enforced while copying. The temporary upload is removed in a `finally` block, including on errors.
- XLSX export uses a local-disk temporary file and removes it after the response is delivered; failed exports are cleaned up immediately.
- Recovery workbook updates use an atomic local-disk temporary file and `os.replace`, then retain only the recovery workbook itself.

Note: the active workbook objects and undo/redo snapshots are still held in memory by the existing application architecture. This change ensures temporary files and upload/export intermediates use local disk; it does not claim that the entire spreadsheet engine is RAM-free.
