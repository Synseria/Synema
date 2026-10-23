#!/usr/bin/env bash
# Install or update the apps listed in tools/apps.txt: GitHub apps from their latest stable release,
# Play Store apps by opening their store page on the TV. An installed app is updated only when its
# versionName differs from the release tag (leading "v" and trailing letters ignored).
#
# Usage: TV_HOST=192.168.1.50 tools/apps.sh            every app in apps.txt
#        TV_HOST=192.168.1.50 tools/apps.sh <package>  just that one
set -euo pipefail
cd "$(dirname "$0")"
. ./tv-host.sh
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
# </dev/null: the loop below reads apps.txt on stdin, which nothing inside it may consume.
tv() { "$PY" -I tv.py "$@" </dev/null | tr -d '\r'; }
ONLY="${1:-}"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

grep -vE '^\s*(#|$)' apps.txt | while read -r pkg src regex; do
  [ -z "$ONLY" ] || [ "$ONLY" = "$pkg" ] || continue
  installed=$(tv "dumpsys package $pkg 2>/dev/null | grep -m1 versionName= | cut -d= -f2" | tail -n 1)
  if [ "$src" = play ]; then
    if [ -n "$installed" ]; then echo "$pkg $installed (Play Store updates it)"
    else tv "am start -a android.intent.action.VIEW -d market://details?id=$pkg" >/dev/null
         echo "$pkg: store page opened on the TV, press Install there"; fi
    continue
  fi
  # One API call per app; unauthenticated GitHub allows 60 an hour.
  read -r tag url < <(curl -fsSL "https://api.github.com/repos/$src/releases/latest" | python3 -I -c '
import json, re, sys
d = json.load(sys.stdin); rx = re.compile(sys.argv[1] + "$")
urls = [a["browser_download_url"] for a in d["assets"] if rx.match(a["name"])]
print(d["tag_name"], urls[0] if urls else "")' "$regex")
  [ -n "$url" ] || { echo "$pkg: no asset matching $regex in $src $tag" >&2; continue; }
  norm() { echo "$1" | sed -E 's/^v//; s/[A-Za-z]+$//'; }
  if [ -n "$installed" ] && [ "$(norm "$installed")" = "$(norm "$tag")" ]; then
    echo "$pkg $installed: up to date"; continue
  fi
  echo "$pkg: ${installed:-not installed} -> $tag"
  curl -fsSL -o "$TMP/app.apk" "$url"
  "$PY" -I b64push.py "$TMP/app.apk" /data/local/tmp/app.apk </dev/null
  tv "pm install -r /data/local/tmp/app.apk; rm -f /data/local/tmp/app.apk" | tail -n 1
done
