#!/usr/bin/env bash
# Give the launcher its keys (Home Assistant's address and token, TMDB's token): pushes
# secrets.local.json (repo root, kept out of git) to the TV as secrets.json in the launcher's private files
# directory, then brings Home back.
#   secrets.local.json: copy secrets.local.json.example and fill it in.
#
# Usage: TV_HOST=192.168.1.50 tools/secrets.sh
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
SRC=../secrets.local.json
python3 -I -c 'import json,sys; c=json.load(open(sys.argv[1])); assert c["ha_url"] and c["ha_token"] and "COLLE" not in c["ha_token"]' "$SRC" 2>/dev/null \
  || { echo "$SRC needs ha_url and ha_token (see the header of this script)" >&2; exit 1; }
PKG=com.synseria.synema
DIR=/sdcard/Android/data/$PKG/files
"$PY" -I tv.py "mkdir -p $DIR" >/dev/null
"$PY" -I tv.py --push "$SRC" "$DIR/secrets.json"
"$PY" -I tv.py "am force-stop $PKG; input keyevent 3; echo 'Secrets set'" | tail -n 1
