#!/bin/sh
# Builds jarrunner.jr.PosixJr for one Linux/macOS target, entirely on this Windows machine:
#   javac (src/main/java-posix) -> TeaVM C backend (BuildDriver, same driver as the Windows build)
#   -> zig cc for the target, with -I bindings/posix: the generated PosixApi carries @Include("jr-posix.h"), so every C file calling it includes the prototypes (PRP-37).
# javac runs teavm_native_check (PRP-35) with the pom's <nativecheck> configuration, so a native-memory
# rule broken in the POSIX tree fails this build just as it fails the Windows one.
# Real-gcc verification (not just zig's bundled clang) is a separate step - see PRP-21's status
# file for why that mattered and how it's checked on WSL.
# Usage: sh build-posix.sh [linux_x64]   (only linux_x64 has real Java to compile the port itself
#   against right now; the other 3 targets exist as jextract_teavm-generated bindings only, in
#   bindings/posix/gen/ - see PRP-21)
T=${1:-linux_x64}
case $T in
  linux_x64) ZT=x86_64-linux-gnu ;;
  *) echo "only linux_x64 is wired up as a real build target so far - see PRP-21's status file"; exit 2 ;;
esac
cd "$(dirname "$0")" || exit 1
ZIG=/c/user/Apps/zig-x86_64-windows-0.16.0/zig
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt || exit 1
CP=$(cat cp.txt)
NC=$(cygpath -w ~/.m2/repository/io/github/jarrunner/teavm_native_check/1.0/teavm_native_check-1.0.jar)
[ -f "$NC" ] || (cd ../teavm_native_check && mvn -q install) || exit 1
NCARGS=$(sed -n 's:.*<nativecheck>\(.*\)</nativecheck>.*:\1:p' pom.xml)
TG=$(sed -n 's:.*<teavm.groupId>\(.*\)</teavm.groupId>.*:\1:p' pom.xml)  # TeaVM's coordinates come from the pom (PRP-37)
V=$(sed -n 's:.*<teavm.version>\(.*\)</teavm.version>.*:\1:p' pom.xml)
M2=$(cygpath -w ~/.m2/repository/$(echo "$TG" | tr . /))
CLASSLIB="$M2\\teavm-classlib\\$V\\teavm-classlib-$V.jar;$M2\\teavm-interop\\$V\\teavm-interop-$V.jar;$M2\\teavm-platform\\$V\\teavm-platform-$V.jar;$M2\\teavm-core\\$V\\teavm-core-$V.jar"
B=build-posix-$T
rm -rf "$B" && mkdir -p "$B/classes"
CHK=src/checks-off/java; [ -n "$CHECKS" ] && CHK=src/checks-on/java  # PRP-35 phase 3: CHECKS=1 sh build-posix.sh for a checks build
javac -d "$B/classes" -cp "$CP" -processorpath "$NC" "-Xplugin:NativeCheck $NCARGS" $(find src/main/java-posix $CHK -name '*.java') || exit 1
java -cp "$CP;$B/classes" jarrunner.jr.build.BuildDriver "$B/classes" "$B/c" jarrunner.jr.PosixJr "$CLASSLIB" > "$B/teavm.log" 2>&1 || { tail -30 "$B/teavm.log"; exit 1; }
"$ZIG" cc -target $ZT -O2 -w -D_GNU_SOURCE -I "$(cygpath -w "$PWD/bindings/posix")" -o "$B/jr-posix" "$B/c/all.c" > "$B/zig.log" 2>&1 || { grep -m20 error "$B/zig.log"; exit 1; }
echo "$T: built $B/jr-posix ($(wc -c < "$B/jr-posix") bytes)"
