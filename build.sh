#!/usr/bin/env bash
# Build the launcher for release, or publish it on GitHub.
#   sh build.sh                    release APK in dist/ (version from the last tag vX.Y.Z)
#   sh build.sh --release          the same (the APK is the shareable file)
#   sh build.sh --publish X.Y.Z    from a clean, pushed-to-be main: tag vX.Y.Z, push main and the tag,
#                                  build, then the GitHub release with the APK and the notes typed in
#                                  dist/notes-vX.Y.Z.md (written before; the commits list otherwise)
[ -n "${BASH_VERSION:-}" ] || exec bash "$0" "$@"
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export PATH="$JAVA_HOME/bin:$PATH"
publish=""
case "${1:-}" in
  ""|--release) ;;
  --publish) publish="${2:?--publish needs a version, e.g. 3.1.0}" ;;
  -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown option $1 (see --help)" >&2; exit 1 ;;
esac

if [ -n "$publish" ]; then
  [[ "$publish" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version X.Y.Z expected, got $publish" >&2; exit 1; }
  [ "$(git branch --show-current)" = main ] || { echo "publish from main" >&2; exit 1; }
  [ -z "$(git status --porcelain)" ] || { echo "commit or drop the changes first" >&2; exit 1; }
  git rev-parse -q --verify "refs/tags/v$publish" >/dev/null && { echo "v$publish exists already" >&2; exit 1; }
  git tag -a "v$publish" -m "v$publish"
  # The build reads its version from the tag; a failed build takes the tag back.
  trap 'git tag -d "v$publish" >/dev/null; echo "** BUILD FAILED ** (tag v$publish removed)" >&2' ERR
fi

(cd launcher && ./gradlew -q assembleRelease --console=plain 2>&1 | { grep -vE '^Note:' || true; })
trap - ERR
version=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || echo v0.0.0)
mkdir -p dist
apk="dist/Synema-$version.apk"
cp launcher/app/build/outputs/apk/release/app-release.apk "$apk"
echo "** BUILD SUCCEEDED ** $apk"

if [ -n "$publish" ]; then
  git push origin main "v$publish"
  notes="dist/notes-v$publish.md"
  if [ -f "$notes" ]; then gh release create "v$publish" "$apk" --title "v$publish" --notes-file "$notes"
  else gh release create "v$publish" "$apk" --title "v$publish" --generate-notes; fi
  echo "** PUBLISHED ** v$publish"
fi
