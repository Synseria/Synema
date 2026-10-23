#!/usr/bin/env bash
# Apply your house on the TV without rebuilding: pushes home.local.json (repo root, kept out of git;
# start from launcher/app/src/main/res/raw/home.json, the example built into the APK) as the
# launcher's home.json overlay, which replaces the built-in one whole, then restarts Home and the
# remote-key service. Without home.local.json the example is pushed. `sh run.sh --install` runs this.
#
# Usage: [TV_HOST=192.168.1.50] tools/home.sh
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
SRC=../home.local.json
[ -f "$SRC" ] || SRC=../launcher/app/src/main/res/raw/home.json
python3 -I -c 'import json,sys; json.load(open(sys.argv[1]))' "$SRC" || { echo "$SRC is not valid JSON" >&2; exit 1; }
PKG=com.synseria.synema
DIR=/sdcard/Android/data/$PKG/files
"$PY" -I tv.py "mkdir -p $DIR" >/dev/null
"$PY" -I tv.py --push "$SRC" "$DIR/home.json"
# The service reads home.json when it starts: switching it off and on makes it read the new one.
./remote-keys.sh off >/dev/null && ./remote-keys.sh on >/dev/null
"$PY" -I tv.py "am force-stop $PKG; input keyevent 3; echo 'home.json applied'" | tail -n 1
