# The TV's address, sourced by every script that drives the TV: TV_HOST when set, else tv_host in
# secrets.local.json (kept out of git), so the address of your own TV is written in one place only.
if [ -z "${TV_HOST:-}" ]; then
  TV_HOST=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("tv_host", ""))' \
    "$(dirname "${BASH_SOURCE[0]}")/../secrets.local.json" 2>/dev/null || true)
fi
: "${TV_HOST:?set tv_host in secrets.local.json, or TV_HOST to the TV IP address (e.g. TV_HOST=192.168.1.50)}"
export TV_HOST
