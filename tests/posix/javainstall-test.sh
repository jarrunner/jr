#!/bin/bash
# PRP-42: jr downloads the app's Java when none is installed (JavaInstall), on macOS and Linux. Downloads Eclipse
# Temurin JRE 25 (about 50 MB) from api.foojay.io into a scratch cache; installed Javas are hidden from jr
# (JR_TEST_FORCE_NO_JAVA, test-only).
# $1 = a TEST build of jr (tests/posix/test-build.sh) whose Os reads its __jrc section from /tmp/jr-test.slot.
set -u
. "$(dirname "$0")/lib.sh"
JR="$1"; W=/tmp/jr42j-install
rm -rf "$W"; mkdir -p "$W"
export JR_TEST_FORCE_NO_JAVA=1 JR_JDK_CACHE_DIR="$W/jdks"
echo '{"java":{"version":"25"},"jvm":{"javaArgs":"-version"},"aot":false}' > /tmp/jr-test.slot

out=$("$JR" < /dev/null 2>&1); code=$?
ok "no terminal and no -Xjr:yes: refused, says how, nothing downloaded" '[ $code = 1 ] && echo "$out" | grep -q "Run it again with -Xjr:yes" && [ ! -e "$W/jdks/25-jre" ]'

# The same from inside an .app, stderr captured, not opened by macOS (a script, a CI runner): no dialog to wait on.
A="$W/Fake.app/Contents"; mkdir -p "$A/MacOS"; cp "$JR" "$A/MacOS/fake"
echo '<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>t.fake</string></dict></plist>' > "$A/Info.plist"
out=$(limit 20 "$A/MacOS/fake" < /dev/null 2>&1); code=$?
ok "inside an .app but not opened by macOS: no dialog, refused within 20 s" '[ $code = 1 ] && echo "$out" | grep -q "Run it again with -Xjr:yes"'

out=$("$JR" -Xjr:yes < /dev/null 2>&1); code=$?
ok "-Xjr:yes: downloads, checks and unpacks JRE 25, then runs it" '[ $code = 0 ] && [ -x "$W/jdks/25-jre/bin/java" ] && echo "$out" | grep -q "version \"25"'
ok "no install leftovers" '[ ! -e "$W/jdks/.jr-install" ]'

start=$(date +%s)
out=$("$JR" < /dev/null 2>&1); code=$?
ok "second run: found in the cache, no question, no download" '[ $code = 0 ] && ! echo "$out" | grep -q "Download" && [ $(( $(date +%s) - start )) -lt 10 ]'

rm -rf "$W/jdks"
echo '{"java":{"version":"25","autoinstall":false},"jvm":{"javaArgs":"-version"},"aot":false}' > /tmp/jr-test.slot
out=$("$JR" -Xjr:yes < /dev/null 2>&1); code=$?
ok "java.autoinstall false: no download, Java Not Found" '[ $code = 1 ] && echo "$out" | grep -q "Java Not Found" && [ ! -e "$W/jdks/25-jre" ]'

rm -f /tmp/jr-test.slot
finish
