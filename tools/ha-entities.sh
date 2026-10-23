#!/usr/bin/env bash
# List Home Assistant entities to pick ids for home.json: id, name and state, filtered by a word.
# Reads the address and token from secrets.local.json; the token goes to curl on stdin, never argv.
#
# Usage: tools/ha-entities.sh [word]      e.g. tools/ha-entities.sh lampe, tools/ha-entities.sh camera.
set -euo pipefail
cd "$(dirname "$0")/.."
URL=$(python3 -I -c 'import json; print(json.load(open("secrets.local.json"))["ha_url"].rstrip("/"))')
python3 -I -c 'import json; print("header = \"Authorization: Bearer " + json.load(open("secrets.local.json"))["ha_token"] + "\"")' \
  | curl -fsS -K - -m 30 "$URL/api/states" \
  | python3 -I -c '
import json, sys
word = sys.argv[1].lower()
for s in sorted(json.load(sys.stdin), key=lambda s: s["entity_id"]):
    name = s["attributes"].get("friendly_name", "")
    if word in s["entity_id"].lower() or word in name.lower():
        print("%-55s %-40s %s" % (s["entity_id"], name[:40], s["state"]))' "${1:-}"
