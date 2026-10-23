#!/usr/bin/env bash
# Build the launcher, or build and put it on the TV.
#   sh run.sh              debug build, verdict only
#   sh run.sh --install    release build installed on the TV (TV_HOST, else tv_host in
#                          secrets.local.json), with the remote-buttons service, the Assistant
#                          button and your house (home.local.json, tools/home.sh) kept on
#   sh run.sh --install --start   the same, then Home on the TV
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export PATH="$JAVA_HOME/bin:$PATH"
install=0 start=0
for a in "$@"; do
  case "$a" in
    --install) install=1 ;;
    --start) start=1 ;;
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option $a (see --help)" >&2; exit 1 ;;
  esac
done
if [ "$install" = 0 ]; then
  # grep finds nothing to print in a clean build: under pipefail its 1 must not count, gradle's does.
  if (cd launcher && ./gradlew -q assembleDebug --console=plain 2>&1 | { grep -vE '^Note:' || true; }); then
    echo "** BUILD SUCCEEDED **"
  else
    echo "** BUILD FAILED **"; exit 1
  fi
  exit 0
fi
. tools/tv-host.sh
REMOTE_KEY_SERVICE=1 launcher/build-and-install.sh 2>&1 | grep -v RuntimeWarning
tools/assistant-key.sh on 2>&1 | grep -v RuntimeWarning
tools/home.sh 2>&1 | grep -v RuntimeWarning
if [ "$start" = 1 ]; then tools/venv/bin/python -I tools/tv.py "input keyevent 3" >/dev/null; fi
echo "** INSTALLED on $TV_HOST **"
