#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
python -m pip install -r requirements.txt
# Development only: bind to loopback, not the LAN.
python -c 'from niceexcel_api.main import app; app.run(host="127.0.0.1", port=8765, debug=False, use_reloader=False, threaded=True)'
