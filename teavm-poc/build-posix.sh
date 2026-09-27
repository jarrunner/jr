#!/bin/sh
# Builds pocapp.jr.PosixJr for one Linux/macOS target, entirely on this Windows machine:
#   javac (src/main/java-posix) -> TeaVM C backend (BuildDriver, same driver as the Windows build)
#   -> zig cc for the target, with jr-posix.h force-included so every @Import call is prototyped.
# Real-gcc verification (not just zig's bundled clang) is a separate step - see PRP-21's status
# file for why that mattered and how it's checked on WSL.
# Usage: sh build-posix.sh [linux_x64]   (only linux_x64 has real Java to compile the port itself
#   against right now; the other 3 targets exist as jextract-teavm bindings only - see PRP-21)
T=${1:-linux_x64}
case $T in
  linux_x64) ZT=x86_64-linux-gnu ;;
  *) echo "only linux_x64 is wired up as a real build target so far - see PRP-21's status file"; exit 2 ;;
esac
cd "$(dirname "$0")" || exit 1
ZIG=/c/user/Apps/zig-x86_64-windows-0.16.0/zig
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt || exit 1
CP=$(cat cp.txt)
M2=$(cygpath -w ~/.m2/repository/org/teavm)
V=0.15.0
CLASSLIB="$M2\\teavm-classlib\\$V\\teavm-classlib-$V.jar;$M2\\teavm-interop\\$V\\teavm-interop-$V.jar;$M2\\teavm-platform\\$V\\teavm-platform-$V.jar;$M2\\teavm-core\\$V\\teavm-core-$V.jar"
B=build-posix-$T
rm -rf "$B" && mkdir -p "$B/classes"
javac -d "$B/classes" -cp "$CP" $(find src/main/java-posix -name '*.java') || exit 1
java -cp "$CP;$B/classes" pocapp.BuildDriver "$B/classes" "$B/c" pocapp.jr.PosixJr "$CLASSLIB" > "$B/teavm.log" 2>&1 || { tail -30 "$B/teavm.log"; exit 1; }
"$ZIG" cc -target $ZT -O2 -w -D_GNU_SOURCE -include "$(cygpath -w "$PWD/../jextract-teavm/jr/jr-posix.h")" -o "$B/jr-posix" "$B/c/all.c" > "$B/zig.log" 2>&1 || { grep -m20 error "$B/zig.log"; exit 1; }
echo "$T: built $B/jr-posix ($(wc -c < "$B/jr-posix") bytes)"
