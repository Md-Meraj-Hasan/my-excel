"""Android entry point for the Nice Excel loopback Flask server.

All runtime writes are redirected to Android's writable app-private filesDir.
The Python source/package directory is treated as read-only.
"""
from __future__ import annotations

import logging
import os
import shutil
import sys
import traceback
from pathlib import Path


def start_server(app_data_dir: str) -> None:
    """Start Flask on loopback and keep all workbook data in filesDir."""
    app_dir = Path(app_data_dir).expanduser().resolve()
    log_file = app_dir / "niceexcel_engine_startup.log"

    # Configure the log before doing filesystem work so PermissionError is captured.
    try:
        app_dir.mkdir(parents=True, exist_ok=True)
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(name)s: %(message)s",
            handlers=[logging.FileHandler(str(log_file), encoding="utf-8"), logging.StreamHandler()],
            force=True,
        )
    except Exception:
        # Keep a console logger even if file logging itself cannot be initialized.
        logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", force=True)

    logger = logging.getLogger("NiceExcelWebEngine")
    try:
        base = app_dir / "niceexcel"
        data_dir = base / "data"
        recovery_dir = data_dir / "recovery"
        temp_dir = data_dir / "temp"
        static_dir = base / "static"

        for directory in (base, data_dir, recovery_dir, temp_dir, static_dir):
            directory.mkdir(parents=True, exist_ok=True)
            # Fail early with the exact path if Android denies a write.
            probe = directory / ".niceexcel_write_test"
            probe.write_text("ok", encoding="utf-8")
            probe.unlink(missing_ok=True)

        # Flask serves a writable copy of the bundled UI; Android assets may be read-only.
        asset_index = app_dir / "niceexcel_asset_index.html"
        if not asset_index.is_file():
            raise FileNotFoundError(f"Web UI asset was not copied to app-private storage: {asset_index}")
        shutil.copyfile(asset_index, static_dir / "index.html")

        os.environ["NICEEXCEL_DATA_DIR"] = str(data_dir)
        os.environ["NICEEXCEL_RECOVERY_DIR"] = str(recovery_dir)
        os.environ["NICEEXCEL_TEMP_DIR"] = str(temp_dir)
        os.environ["NICEEXCEL_STATIC_DIR"] = str(static_dir)
        # Ensure any library using tempfile/TMPDIR writes to app-private local disk.
        os.environ["TMPDIR"] = str(temp_dir)
        os.environ["TMP"] = str(temp_dir)
        os.environ["TEMP"] = str(temp_dir)
        import tempfile
        tempfile.tempdir = str(temp_dir)

        # Ensure the app's bundled Python package can be found, without writing into it.
        python_root = str(Path(__file__).resolve().parent)
        if python_root not in sys.path:
            sys.path.insert(0, python_root)

        logger.info("Starting NiceExcel local engine; Python=%s", sys.version)
        logger.info("App private directory: %s", app_dir)
        logger.info("Writable data directory: %s", data_dir)
        logger.info("Writable recovery directory: %s", recovery_dir)
        logger.info("Writable temporary directory: %s", temp_dir)
        logger.info("Writable static directory: %s", static_dir)

        from niceexcel_api.main import app

        logger.info("Flask version: %s", __import__("flask").__version__)
        logger.info("Starting Flask on loopback 127.0.0.1:8765")
        # Flask's development server is sufficient for this single-user, local-only
        # Android WebView engine. Never bind to 0.0.0.0 or expose it to the LAN.
        app.run(host="127.0.0.1", port=8765, debug=False, use_reloader=False, threaded=True)
        logger.error("Flask returned; the local server is no longer running")
    except BaseException:
        logger.error("Flask engine startup/runtime failure:\n%s", traceback.format_exc())
        raise
