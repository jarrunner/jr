#!/bin/bash
# PRP-42: -Xjr:install, the bundle refresh on launch, and -Xjr:update inside a jr-written .app, on Linux and macOS.
# $1 = a TEST build of jr (tests/posix/test-build.sh) whose Os reads its __jrc section from /tmp/jr-test.slot, with
# app bundles on. $2, $3 = sections jr-maven-plugin wrote for versions 1.0 and 1.1 (fixtures/slot-1.*.bin, made by
# the plugin's MacOutputsTest#dumpSectionForJrTests).
set -u
. "$(dirname "$0")/lib.sh"
JR="$1"; SLOT10="$2"; SLOT11="$3"; PORT=8443
W="$(cd /tmp && pwd -P)/jr42i"   # /tmp is a symlink on macOS
rm -rf "$W"; mkdir -p "$W/home" "$W/dl" "$W/srv"; export HOME="$W/home"
APP="$HOME/Applications/Hello World.app"; LINK="$HOME/.local/bin/hello"
cp "$SLOT10" /tmp/jr-test.slot
cp "$JR" "$W/dl/hello"; chmod 755 "$W/dl/hello"

out=$("$W/dl/hello" -Xjr:install 2>&1); code=$?
echo "$out" | sed 's/^/    /'
ok "install: exit 0" '[ $code = 0 ]'
ok "install: Info.plist, PkgInfo, icon written" '[ -f "$APP/Contents/Info.plist" ] && [ "$(cat "$APP/Contents/PkgInfo")" = "APPL????" ] && [ "$(head -c 4 "$APP/Contents/Resources/hello.icns")" = icns ]'
ok "install: Info.plist is version 1.0" 'grep -A1 CFBundleVersion "$APP/Contents/Info.plist" | grep -q "<string>1.0</string>"'
ok "install: the binary copied in, executable, same bytes" '[ -x "$APP/Contents/MacOS/hello" ] && cmp -s "$JR" "$APP/Contents/MacOS/hello"'
ok "install: command link points into the app" '[ "$(readlink "$LINK")" = "$APP/Contents/MacOS/hello" ]'
ok "install: no .jr-new left" '[ -z "$(find "$HOME" -name "*.jr-new")" ]'
ok "install: says ~/.local/bin is not on PATH" 'echo "$out" | grep -q "not on your PATH"'

out=$("$LINK" -Xjr:install 2>&1); code=$?
ok "install again, run through the link: exit 0, link kept" '[ $code = 0 ] && [ "$(readlink "$LINK")" = "$APP/Contents/MacOS/hello" ]'

# the binary now "carries" 1.1 (as after -Xjr:update): a normal launch through the link rewrites the bundle
cp "$SLOT11" /tmp/jr-test.slot
"$LINK" >/dev/null 2>&1
ok "refresh: Info.plist rewritten to 1.1 on launch" 'grep -A1 CFBundleVersion "$APP/Contents/Info.plist" | grep -q "<string>1.1</string>"'

mkdir -p "$APP/Contents/_CodeSignature"; cp "$SLOT10" /tmp/jr-test.slot
"$LINK" >/dev/null 2>&1
ok "refresh: a sealed bundle is left alone" 'grep -A1 CFBundleVersion "$APP/Contents/Info.plist" | grep -q "<string>1.1</string>"'
rmdir "$APP/Contents/_CodeSignature"

rm "$LINK"; echo other > "$LINK"
out=$("$W/dl/hello" -Xjr:install 2>&1)
ok "install: another file at the link's place is not replaced" '[ "$(cat "$LINK")" = other ] && echo "$out" | grep -q "another file"'
rm "$LINK"; "$W/dl/hello" -Xjr:install >/dev/null 2>&1

# -Xjr:update inside the jr-written app uses the single-file entry and replaces only Contents/MacOS/hello
cd "$W/srv" || exit 1
serve_https
cp "$JR" new.bin; printf 'X' >> new.bin
SHA=$(sha new.bin)
cat > app.update.json <<J
{"format":1,"app":"io.github.example:hello","channels":{"stable":"1.1"},"releases":[
 {"version":"1.1","exe":{"$KEY":{"sha256":"$SHA","urls":["https://localhost:$PORT/new.bin"]}},
  "app":{"$KEY":{"sha256":"$SHA","urls":["https://localhost:$PORT/should-not-be-used.zip"]}}},
 {"version":"1.0"}]}
J
out=$("$LINK" -Xjr:update 2>&1); code=$?
echo "$out" | sed 's/^/    /'
ok "update in the app: exit 0, binary replaced from the exe entry" '[ $code = 0 ] && [ "$(sha "$APP/Contents/MacOS/hello")" = "$SHA" ]'
ok "update in the app: link still points at it, bundle still there" '[ "$(readlink "$LINK")" = "$APP/Contents/MacOS/hello" ] && [ -f "$APP/Contents/Info.plist" ]'

rm -f /tmp/jr-test.slot
finish
