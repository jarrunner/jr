#!/bin/bash
# PRP-42: jvm=dll (JliLauncher: the JVM inside jr's process through libjli), on Linux and macOS.
# $1 = a jr build (a release build is fine), $2 = a JDK home (Java 21+). Builds its own test jar with that JDK.
# On macOS this is also the test of jr-posix.h's main(): libjli calls main() a second time on a new thread there, and
# jr must send that call back into JLI_Launch instead of starting TeaVM again. If it does not, the run crashes or
# hangs, so every run here has a time limit.
set -u
. "$(dirname "$0")/lib.sh"
JR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; J="$2"
W="$(cd /tmp && pwd -P)/jr42j"
rm -rf "$W"; mkdir -p "$W"; cd "$W" || exit 1
cp "$JR" "$W/jr"; chmod 755 "$W/jr"; JR="$W/jr"
cat > Who.java <<'J'
public class Who {
    public static void main(String[] a) {
        System.out.println("cmd=" + ProcessHandle.current().info().command().orElse("?"));
        System.out.println("args=" + String.join("|", a));
        System.exit(a.length > 0 && a[0].equals("seven") ? 7 : 0);
    }
}
J
"$J/bin/javac" --release 21 Who.java && printf 'Main-Class: Who\n' > m.txt && "$J/bin/jar" cfm who.jar m.txt Who.class || { echo "could not build the test jar"; exit 1; }
nm "$JR" 2>/dev/null | grep -E ' _?(main|jr_teavm_main|jx_jli_start)$' | sed 's/^/    /'

out=$(limit 120 "$JR" -Xjr:java.home="$J" who.jar a "b c" 2>&1); code=$?
ok "child (default): runs, java is the process" '[ $code = 0 ] && echo "$out" | grep -q "cmd=.*/bin/java" && echo "$out" | grep -q "args=a|b c"'

out=$(limit 120 "$JR" -Xjr:java.home="$J" -Xjr:jvm=dll who.jar seven "b c" 2>&1); code=$?
echo "$out" | sed 's/^/    dll: /'
ok "jvm=dll: the JVM runs inside jr (cmd is jr), args intact" 'echo "$out" | grep -q "cmd=$JR\$" && echo "$out" | grep -q "args=seven|b c"'
ok "jvm=dll: the app exit code comes back (7)" '[ $code = 7 ]'

out=$(limit 120 "$JR" -Xjr:java.home="$J" -Xjr:jvm=dll -Xjr:log.file="$W/jr.log" who.jar 2>&1); code=$?
ok "jvm=dll: log says in-process" '[ $code = 0 ] && grep -q "Invoking in-process JVM" "$W/jr.log"'

if [ "$(uname -s)" = Linux ]; then
    out=$(LD_LIBRARY_PATH=/nonexistent limit 120 "$JR" -Xjr:java.home="$J" -Xjr:jvm=dll -Xjr:log.file="$W/jr.log" who.jar 2>&1); code=$?
    ok "jvm=dll with LD_LIBRARY_PATH set: falls back to a child" '[ $code = 0 ] && echo "$out" | grep -q "cmd=.*/bin/java" && grep -q "LD_LIBRARY_PATH is set" "$W/jr.log"'
fi

out=$(limit 120 "$JR" -Xjr:java.home=/nonexistent -Xjr:jvm=dll who.jar 2>&1); code=$?
ok "jvm=dll with a bad java.home: clean error, exit 1" '[ $code = 1 ]'

out=$(limit 120 "$JR" -Xjr:java.home="$J" -Xjr:jvm=dll -Xjr:log.file="$W/jr.log" who.jar 2>&1); code=$?
ok "jvm=dll: a second run reuses the AOT cache" '[ $code = 0 ] && grep -q "Using existing AOT cache" "$W/jr.log"'

finish
