#!/bin/bash
# PRP-42: -Xjr:update / -Xjr:update-check / -Xjr:batch for a bare binary, on Linux and macOS.
# $1 = a TEST build of jr (tests/posix/test-build.sh) whose Os reads its __jrc section from /tmp/jr-test.slot.
# The update is served over local HTTPS.
set -u
. "$(dirname "$0")/lib.sh"
JR="$1"; W=/tmp/jr42; PORT=8443
rm -rf "$W"; mkdir -p "$W/srv" "$W/app" "$W/ro"; cd "$W/srv" || exit 1
serve_https
cp "$JR" "$W/app/myapp"; chmod 750 "$W/app/myapp"
cp "$JR" new.bin; printf 'X' >> new.bin            # a different file that still runs
SHA=$(sha new.bin)
upd() { # $1 sha  $2 platform key
cat > app.update.json <<J
{"format":1,"app":"t:myapp","channels":{"stable":"1.1"},"releases":[
 {"version":"1.1","exe":{"$2":{"sha256":"$1","urls":["https://localhost:$PORT/new.bin"]}}},
 {"version":"1.0"}]}
J
}
cat > /tmp/jr-test.slot <<J
{"app":{"id":"t:myapp","version":"1.0"},"update":{"url":"https://localhost:$PORT/app.update.json"}}
J
before=$(sha "$W/app/myapp")

upd "$SHA" "$KEY"
out=$("$W/app/myapp" -Xjr:update-check 2>&1); code=$?
ok "update-check reports newer (exit 10)" '[ $code = 10 ] && echo "$out" | grep -q "1.0 -> 1.1"'

upd 0000000000000000000000000000000000000000000000000000000000000000 "$KEY"
out=$("$W/app/myapp" -Xjr:update 2>&1); code=$?
ok "wrong sha256: refused, binary untouched, no leftovers" '[ $code = 1 ] && [ "$(sha $W/app/myapp)" = "$before" ] && [ ! -e $W/app/myapp.jr-download ]'

upd "$SHA" no-such-platform
out=$("$W/app/myapp" -Xjr:update 2>&1); code=$?
ok "no entry for this platform: refused with the keys named" '[ $code = 1 ] && echo "$out" | grep -q "$KEY"'

cp "$JR" "$W/ro/myapp"; chmod 555 "$W/ro"
upd "$SHA" "$KEY"
out=$("$W/ro/myapp" -Xjr:update 2>&1); code=$?
ok "unwritable folder: refused before downloading" '[ $code = 1 ] && echo "$out" | grep -q "cannot write to" && ! echo "$out" | grep -q Downloading'
chmod 755 "$W/ro"

out=$("$W/app/myapp" -Xjr:update 2>&1); code=$?
echo "$out" | sed 's/^/    /'
ok "update: exit 0, binary is the new file" '[ $code = 0 ] && [ "$(sha $W/app/myapp)" = "$SHA" ]'
ok "update: mode 750 kept" '[ "$(mode $W/app/myapp)" = 750 ]'
ok "update: no .jr-download left" '[ ! -e $W/app/myapp.jr-download ]'
ok "updated binary runs" '"$W/app/myapp" -Xjr:help >/dev/null 2>&1'

# a running instance keeps running while its file is replaced (rename gives the new file a new inode)
cp "$JR" "$W/app/myapp"; chmod 755 "$W/app/myapp"
ino1=$(inode "$W/app/myapp")
"$W/app/myapp" -Xjr:update >/dev/null 2>&1
ok "update: new inode (macOS needs this for signed binaries)" '[ "$(inode $W/app/myapp)" != "$ino1" ]'

# -Xjr:batch: stdout is exactly one JSON line, messages go to stderr, exit codes unchanged
cp "$JR" "$W/app/myapp"; chmod 755 "$W/app/myapp"
out=$("$W/app/myapp" -Xjr:update-check -Xjr:batch 2>/dev/null); code=$?
ok "batch check: exit 10, one JSON line with status newer" '[ $code = 10 ] && [ "$(echo "$out" | wc -l | tr -d " ")" = 1 ] && echo "$out" | grep -q "\"status\":\"newer\",\"version\":\"1.0\",\"latest\":\"1.1\""'
out=$("$W/app/myapp" -Xjr:update -Xjr:batch 2>/dev/null); code=$?
ok "batch update: exit 0, status updated, nothing else on stdout" '[ $code = 0 ] && [ "$(echo "$out" | wc -l | tr -d " ")" = 1 ] && echo "$out" | grep -q "\"status\":\"updated\"" && [ "$(sha $W/app/myapp)" = "$SHA" ]'
upd 0000000000000000000000000000000000000000000000000000000000000000 "$KEY"
cp "$JR" "$W/app/myapp"; chmod 755 "$W/app/myapp"
out=$("$W/app/myapp" -Xjr:update -Xjr:batch 2>/dev/null); code=$?
ok "batch update, bad sha256: exit 1, status error" '[ $code = 1 ] && echo "$out" | grep -q "\"status\":\"error\""'

rm -f /tmp/jr-test.slot
finish
