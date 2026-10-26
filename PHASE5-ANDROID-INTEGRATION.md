# Nice Excel Phase 5 — Android FastAPI Integration

This phase adds an **optional** FastAPI-backed Web Excel screen while retaining the existing native Kotlin spreadsheet screen.

## What changed
- Added Chaquopy to bundle Python 3.11 and the runtime dependencies (FastAPI, Uvicorn, openpyxl, python-multipart).
- Added `FastApiActivity`, which opens the existing web spreadsheet UI in Android WebView.
- Added `Web` entry in the native ribbon; the original native screen is still the app's launcher.
- FastAPI binds only to `127.0.0.1:8765`; it is not exposed on the LAN. Workbook recovery files are directed to app-private storage.
- The static UI is copied from Android assets into app-private storage because Starlette `StaticFiles` needs a real filesystem directory.

## Important limitations
- This is an integration milestone, not a verified production release. The APK must be built in GitHub Actions and tested on a real Android device.
- Android's `INTERNET` permission is required for loopback TCP sockets. The Python server binds only to loopback and the bundled code does not call external services; this is not a formal OS-level guarantee that the app cannot access the network.
- The FastAPI screen is separate from the native workbook state. It currently uses its own backend workbook sessions; it does not automatically import the native Kotlin workbook.
- Formula support remains a limited custom evaluator, not full Microsoft Excel compatibility.
- Python/Chaquopy package availability and APK size need validation by the CI build.

## CI
The existing workflow continues to run backend pytest tests and Android unit tests, then assembles the debug APK. A successful GitHub Actions run and device smoke test are required before treating integration as verified.
