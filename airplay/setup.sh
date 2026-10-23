#!/usr/bin/env bash
# Sets the TV's AirPlay receiver (jqssun/android-airplay-server, installed by tools/apps.sh) for this
# 4K panel: it asks senders for 3840x2160 in H.265, at 60 fps, with the decoder's low-latency mode.
# Settings go through the receiver's own adb-only broadcast; the server restarts by itself. It may also
# draw over other apps, which is what lets it come to the front when a sender connects.
#
# Usage: TV_HOST=192.168.1.50 airplay/setup.sh [name]      default name: keeps the current one
#        TV_HOST=192.168.1.50 airplay/setup.sh --show      prints every setting
set -euo pipefail
. "$(dirname "$0")/../tools/tv-host.sh"
adb connect "$TV_HOST:5555" >/dev/null
tv() { adb -s "$TV_HOST:5555" shell "$@"; }
RECEIVER=io.github.jqssun.airplay/.SettingsReceiver
set_() { tv "am broadcast -n $RECEIVER -a io.github.jqssun.airplay.SET_SETTING --es key '$1' --es value '$2'" >/dev/null && echo "$1 = $2"; }
if [ "${1:-}" = --show ]; then
  tv "am broadcast -n $RECEIVER -a io.github.jqssun.airplay.LIST_SETTINGS" | sed -n 's/.*data="\(.*\)"/\1/p' \
    | python3 -c 'import json,sys; [print(k.ljust(34), v["value"]) for k,v in json.load(sys.stdin).items()]'
  exit 0
fi
[ -n "${1:-}" ] && set_ server_name "$1"
# The panel is 4K even though Android draws its menus at 1080p: "auto" would ask for 1080p.
set_ resolution 3840x2160
# 4K mirroring needs H.265; senders without it (older iPhones) fall back to H.264 at 1080p.
set_ h265 on
set_ max_fps 60
set_ low_latency true
# The receiver brings its screen forward when a sender connects, from its background service: Android
# blocks that ("Background activity start") unless the app may draw over others, which a TV never asks.
tv "appops set io.github.jqssun.airplay SYSTEM_ALERT_WINDOW allow" && echo "comes forward on connection"
