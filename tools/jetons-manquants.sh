#!/usr/bin/env bash
# The tokens the code asks of the design system and does not have yet ("jeton manquant: <name> (<value>)"),
# one per line with where it is used: the list to carry to Synseria-Design.
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -euo pipefail
cd "$(dirname "$0")/../launcher/app/src/main"
grep -rnE 'jeton manquant:' java res --include='*.java' --include='*.xml' | grep -v '^res/values/jetons.xml:' \
  | sed -E 's/^([^:]+:[0-9]+):.*jeton manquant: *(.*)$/\2\t\1/; s/ *(-->|\*\/)? *$//; s/[ .]+$//' | sort
