#!/usr/bin/env bash
# The remote's Assistant button: the house assistant (on) or Google Assistant (off).
# The system answers that button before any app sees it, so `on` gives every remote the launcher's
# key layout overlay (KeyLayouts: the button becomes F12, which RemoteKeyService turns into the
# assistant). Run it again after pairing a new remote. Needs the launcher installed.
#
# Usage: TV_HOST=192.168.1.50 tools/assistant-key.sh on | off
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
case "${1:-}" in
  on|off) "$PY" -I tv.py "CLASSPATH=\$(pm path com.synseria.synema | cut -d: -f2) app_process /system/bin com.synseria.synema.KeyLayouts $1" ;;
  *) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
