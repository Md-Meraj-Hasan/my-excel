"""Android entry point for the Nice Excel loopback FastAPI server."""
from __future__ import annotations

import os
import shutil
from pathlib import Path


def start_server(app_data_dir: str) -> None:
    """Start Uvicorn on loopback only; workbook data stays in app-private storage."""
    base = Path(app_data_dir) / "niceexcel"
    data_dir = base / "data"
    recovery_dir = data_dir / "recovery"
    static_dir = base / "static"
    recovery_dir.mkdir(parents=True, exist_ok=True)
    static_dir.mkdir(parents=True, exist_ok=True)

    # Static assets are copied from Android assets because Python modules may be
    # loaded from an archive and StaticFiles requires a real filesystem directory.
    asset_index = Path(app_data_dir) / "niceexcel_asset_index.html"
    if asset_index.exists():
        shutil.copy2(asset_index, static_dir / "index.html")

    os.environ["NICEEXCEL_DATA_DIR"] = str(data_dir)
    os.environ["NICEEXCEL_RECOVERY_DIR"] = str(recovery_dir)
    os.environ["NICEEXCEL_STATIC_DIR"] = str(static_dir)

    import uvicorn
    uvicorn.run(
        "niceexcel_api.main:app",
        host="127.0.0.1",
        port=8765,
        log_level="warning",
        access_log=False,
    )
