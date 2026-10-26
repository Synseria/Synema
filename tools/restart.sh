#!/usr/bin/env bash
# Restart the launcher on the TV so it reads what a script just pushed (home.json, secrets.json, a
# theme), then show Home. A force-stop takes the remote-key service down with it: Android drops a
# stopped app's accessibility services, and one stopped while bound stays "crashed", never binding
# again until the next install. So the service, when on, is switched off first and back on after.
#
# Usage: [TV_HOST=192.168.1.50] tools/restart.sh [message to print when done]
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.synseria.synema
# The pause lets the unbind finish first: on Android 11 a stop racing it can crash system_server.
"$PY" -I tv.py "s=\$(settings get secure enabled_accessibility_services); \
others=\$(echo \"\$s\" | tr ':' '\n' | grep -v '^$PKG/' | paste -sd: -); \
[ \"\$others\" = \"\$s\" ] || { settings put secure enabled_accessibility_services \"\${others:-null}\"; sleep 2; }; \
am force-stop $PKG; \
[ \"\$others\" = \"\$s\" ] || settings put secure enabled_accessibility_services \"\$s\"; \
input keyevent 3; echo '${1:-launcher restarted}'" | tail -n 1
