#!/usr/bin/env bash
# Build the launcher for release, or publish it on GitHub.
#   sh build.sh                    release APK in dist/ (version from the last tag vX.Y.Z)
#   sh build.sh --release          the same (the APK is the shareable file)
#   sh build.sh --publish X.Y.Z[-rc.N] [--correctif]   from a clean rc: build check, publier.sh (changelog,
#                                  tag, push; skill publier), build at the tag, GitHub release with the APK
#                                  and the notes of CHANGELOG.md (a pre-release for an rc)
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export PATH="$JAVA_HOME/bin:$PATH"
publish=""; correctif=""
case "${1:-}" in
  ""|--release) ;;
  --publish) publish="${2:?--publish needs a version, e.g. 3.1.0}"; correctif="${3:-}" ;;
  -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown option $1 (see --help)" >&2; exit 1 ;;
esac

build() { (cd launcher && ./gradlew -q assembleRelease --console=plain 2>&1 | { grep -vE '^Note:' || true; }); }

# The build reads its version from the tag: a first build proves the code compiles before anything is
# tagged or pushed, the second one (incremental) carries the new version.
if [ -n "$publish" ]; then
  build
  bash "$HOME/.claude/skills/publier/publier.sh" "$publish" ${correctif:+"$correctif"}
fi
build
version=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || echo v0.0.0)
mkdir -p dist
apk="dist/Synema-$version.apk"
cp launcher/app/build/outputs/apk/release/app-release.apk "$apk"
echo "** BUILD SUCCEEDED ** $apk"

if [ -n "$publish" ]; then
  prerelease=""; [[ "$publish" == *-rc.* ]] && prerelease="--prerelease"
  gh release create "v$publish" "$apk" $prerelease --title "v$publish" \
    --notes "$(python3 "$HOME/.claude/skills/publier/journal.py" notes "$publish")"
  echo "** PUBLISHED ** v$publish"
fi
