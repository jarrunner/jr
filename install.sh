#!/bin/sh
# Installs jr on macOS from the GitHub release:
#   curl -fsSL https://github.com/jarrunner/jr/releases/latest/download/install.sh | sh
# It downloads the universal jr-macos (Apple silicon and Intel), checks it against the release's
# SHA256SUMS, and installs it as ~/.local/bin/jr. No admin rights needed.
# Why curl and not a browser download: jr is signed ad hoc, not with an Apple Developer ID, and
# macOS refuses such a file once a browser has marked it as downloaded (and keeps refusing that
# file after). curl sets no such mark.
# Settings: JR_VERSION=1.2.0 for a specific release (default: the latest),
#           JR_INSTALL_DIR=/some/dir for another folder (default: ~/.local/bin),
#           JR_BASE_URL=<url> to download from somewhere other than GitHub.
set -eu

if [ "$(uname -s)" != Darwin ]; then
  echo "jr: this installer is for macOS; Windows builds are on https://github.com/jarrunner/jr/releases" >&2
  exit 1
fi

if [ -n "${JR_BASE_URL:-}" ]; then         # a mirror, or file:///dir for testing
  BASE="$JR_BASE_URL"
elif [ -n "${JR_VERSION:-}" ]; then
  BASE="https://github.com/jarrunner/jr/releases/download/v${JR_VERSION#v}"
else
  BASE="https://github.com/jarrunner/jr/releases/latest/download"
fi
DIR="${JR_INSTALL_DIR:-$HOME/.local/bin}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "jr: downloading from $BASE"
curl -fsSL -o "$TMP/jr-macos" "$BASE/jr-macos"
curl -fsSL -o "$TMP/SHA256SUMS" "$BASE/SHA256SUMS"

WANT=$(awk '$2 == "jr-macos" { print $1 }' "$TMP/SHA256SUMS")
GOT=$(shasum -a 256 "$TMP/jr-macos" | awk '{ print $1 }')
if [ -z "$WANT" ] || [ "$WANT" != "$GOT" ]; then
  echo "jr: checksum mismatch (expected ${WANT:-none}, got $GOT); nothing installed" >&2
  exit 1
fi

mkdir -p "$DIR"
chmod +x "$TMP/jr-macos"
xattr -d com.apple.quarantine "$TMP/jr-macos" 2>/dev/null || true
mv -f "$TMP/jr-macos" "$DIR/jr"
echo "jr: installed $DIR/jr (sha256 $GOT)"

case ":$PATH:" in
  *":$DIR:"*) ;;
  *) echo "jr: $DIR is not on your PATH; add this to ~/.zshrc:  export PATH=\"$DIR:\$PATH\"" ;;
esac
"$DIR/jr" -Xjr:help 2>&1 | head -1 || true
