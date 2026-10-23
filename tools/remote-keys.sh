#!/usr/bin/env bash
# Remote buttons: find their keycodes and switch RemoteKeyService (keys of home.json) on or off.
#
# Usage: TV_HOST=192.168.1.50 tools/remote-keys.sh learn [seconds]   press buttons, read their keycodes
#        TV_HOST=192.168.1.50 tools/remote-keys.sh on | off | status
# `learn` needs the service on: it sees every key, and logs them only while this script asks it to.
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
SVC=com.synseria.synema/com.synseria.synema.RemoteKeyService
TAG=TvHome.RemoteKey
tv() { "$PY" -I tv.py "$@"; }

# Other enabled services are kept: the setting is a colon-separated list.
case "${1:-}" in
  on)
    tv "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *RemoteKeyService*) ;; null|'') settings put secure enabled_accessibility_services $SVC ;; *) settings put secure enabled_accessibility_services \"\$s:$SVC\" ;; esac; settings put secure accessibility_enabled 1; echo RemoteKeyService on" ;;
  off)
    tv "s=\$(settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v RemoteKeyService | paste -sd: -); settings put secure enabled_accessibility_services \"\${s:-null}\"; echo RemoteKeyService off" ;;
  status)
    tv "settings get secure enabled_accessibility_services" ;;
  learn)
    secs="${2:-60}"
    echo "Press the buttons now, a few seconds apart ($secs s). Columns: keycode, scancode, -> app if reassigned."
    echo "Raw lines (device, KEY_...) are buttons the system answers before any app, such as Assistant."
    tv "setprop log.tag.$TAG VERBOSE; logcat -c; (timeout $secs getevent -l 2>/dev/null | grep --line-buffered 'EV_KEY.*DOWN' &); timeout $secs logcat -v brief -s $TAG:V GlobalKeyRcv:D | grep -E 'keycode|map to'; setprop log.tag.$TAG ''" || true
    ;;
  *) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
