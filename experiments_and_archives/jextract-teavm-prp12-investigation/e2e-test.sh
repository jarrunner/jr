#!/bin/sh
# End-to-end run of one built jr variant against real jars: help text, arg + exit-code passthrough in both
# launch modes (java.exe child process, and jvm.dll in-process via LoadLibrary/GetProcAddress/JLI_Launch),
# .jrc config mode, AOT cache creation (FindFirstFile + WIN32_FIND_DATAA offsets), stdin passthrough.
# Prints one normalised line per check, so two variants can be diffed. Usage: sh e2e-test.sh gen|hand
V=$1
HERE=$(cd "$(dirname "$0")" && pwd)
EXE=$HERE/app-$V/jr-$V-opt.exe
T=$HERE/e2e-$V
rm -rf "$T"; mkdir -p "$T"; cd "$T" || exit 1
cp "$HERE/../../test-scripts/JvmModeTest.jar" .
cp "$EXE" jr.exe

norm() { sed -E 's/pid +=.*/pid = N/; s/launcher.start.micros = .*/launcher.start.micros = N/' | tr -d '\r'; }
check() { echo "== $1 (exit $2)"; }

./jr.exe > help.txt 2>&1; check "no-args help, $(wc -l < help.txt) lines" $?

./jr.exe -jar JvmModeTest.jar alpha "two words" --report=r1.txt --exit=7 > /dev/null 2>&1; check "java.exe mode, exit passthrough" $?
norm < r1.txt | grep -E 'argc|arg\[|process ' | sed -E 's#process += .*[\\/]#process = .../#'

printf 'jvm=dll\njava.args=-jar JvmModeTest.jar --report=r2.txt --exit=5\n' > app.jrc
cp jr.exe app.exe
./app.exe extra-arg > /dev/null 2>&1; check ".jrc config + jvm.dll in-process mode" $?
norm < r2.txt | grep -E 'argc|arg\[|process ' | sed -E 's#process += .*[\\/]#process = .../#'

./app.exe > /dev/null 2>&1; check "second run (AOT cache present)" $?
echo "aot cache files: $(ls | grep -ciE '\.aot$')"

echo "hello-from-stdin" | ./jr.exe -jar JvmModeTest.jar --stdin --report=r3.txt > /dev/null 2>&1; check "stdin passthrough" $?
grep -i stdin r3.txt | tr -d '\r'
