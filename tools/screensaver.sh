#!/usr/bin/env bash
# Turn on the launcher's screensaver (ClockDream: drifting clock and house status), which keeps an OLED
# panel from burning in a still home screen. Android TV starts it after the "screen off" delay.
#
# Usage: TV_HOST=192.168.1.50 tools/screensaver.sh [minutes]   default 10
#        TV_HOST=192.168.1.50 tools/screensaver.sh off
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
if [ "${1:-}" = off ]; then
  "$PY" -I tv.py "settings put secure screensaver_enabled 0; echo screensaver off"
  exit 0
fi
MIN="${1:-10}"
case "$MIN" in ''|*[!0-9]*) echo "minutes must be a number" >&2; exit 1 ;; esac
"$PY" -I tv.py "settings put secure screensaver_components com.synseria.synema/.ClockDream; settings put secure screensaver_enabled 1; settings put secure screensaver_activate_on_sleep 1; settings put system screen_off_timeout $((MIN * 60000)); echo screensaver after $MIN min"
