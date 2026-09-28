#!/bin/sh
# Before/after parity for the jr-20 phase-1 features, run from a scratch dir. Usage: sh parity.sh <jr.exe> <dir> <jdkcache>
EXE=$1; T=$2; CACHE=$3
rm -rf "$T"; mkdir -p "$T"; cd "$T" || exit 1
cp "$EXE" jr.exe; cp /c/user/code/littlejlib/jr/test-scripts/JvmModeTest.jar .
run() { echo "== $1"; shift; "$@" 2>&1 | tr -d '\r' | sed -E "s#$(pwd -W | sed 's#/#\\\\#g')#<T>#g; s#pid +=.*#pid = N#; s#micros = .*#micros = N#"; echo "exit=$?"; }
run "-Xjr:help" ./jr.exe -Xjr:help
run "old flag --help" ./jr.exe --help
run "unknown -Xjr option" ./jr.exe -Xjr:bogus
run "create-config" ./jr.exe -Xjr:create-config=JvmModeTest.jar
[ -f jr.jrc ] && { echo "-- jr.jrc:"; tr -d '\r' < jr.jrc; }
printf 'java.version=25\njava.args=-jar JvmModeTest.jar --report=v25.txt --exit=3\n' > v25.jrc; cp jr.exe v25.exe
run "java.version=25 from cache" env JR_TEST_FORCE_NO_JAVA=1 JR_JDK_CACHE_DIR="$(cygpath -w "$CACHE")" ./v25.exe
grep -E "java.version" v25.txt | tr -d '\r'
printf 'java.version=21+\njava.args=-jar JvmModeTest.jar --report=v21.txt --exit=4\n' > v21.jrc; cp jr.exe v21.exe
run "java.version=21+ from cache" env JR_TEST_FORCE_NO_JAVA=1 JR_JDK_CACHE_DIR="$(cygpath -w "$CACHE")" ./v21.exe
grep -E "java.version" v21.txt | tr -d '\r'
printf 'java.version=99\njava.args=-jar JvmModeTest.jar\n' > v99.jrc; cp jr.exe v99.exe
echo "== java.version=99, declined"; echo n | env JR_TEST_FORCE_NO_JAVA=1 JR_JDK_CACHE_DIR="$(cygpath -w "$CACHE")" ./v99.exe 2>&1 | tr -d '\r' | tail -4; echo "exit=$?"
