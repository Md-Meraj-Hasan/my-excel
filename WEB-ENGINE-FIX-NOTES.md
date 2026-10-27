# NiceExcel Web Engine startup fix

## Changes
- `app/src/main/python/server.py`: all runtime write paths are explicitly rooted in Android `filesDir`; validates that runtime directories are writable; copies the bundled HTML into writable private storage; sets data/recovery/static environment variables before importing the FastAPI app; imports the app after those variables are set and starts Uvicorn with the app object; logs full startup exceptions.
- `app/src/main/java/com/meraj/niceexcel/FastApiActivity.kt`: checks `/health` rather than only testing whether the port accepts a socket; reuses a running local engine when returning to the Web screen; waits for the health endpoint before loading WebView; reports log location on failure.

## Validation
- Python syntax compilation passed for `server.py` and `niceexcel_api/main.py`.
- Local smoke test passed: `/health` returned `{"status":"ok","service":"nice-excel-api","version":"0.1.0","offline_ready":true}`.
- Android Gradle build/device test was not run in this environment.
