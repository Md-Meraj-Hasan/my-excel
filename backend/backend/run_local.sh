#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
python -m pip install -r requirements.txt
# Development only: bind to loopback, not the LAN.
python -m uvicorn niceexcel_api.main:app --host 127.0.0.1 --port 8765
