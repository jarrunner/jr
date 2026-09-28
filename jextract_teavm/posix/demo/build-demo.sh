#!/bin/sh
# Builds posixdemo.PosixDemo for one POSIX target, entirely on this Windows machine:
#   javac (demo source + ../gen/<target>, the generated bindings for that platform)
#   -> TeaVM C backend (the same BuildDriver as jr; no postprocess: that script only fixes mingw)
#   -> zig cc for the target, with the real platform headers force-included so every @Import call is prototyped
#      (-D_GNU_SOURCE because the force-include runs before all.c's own feature macros; TeaVM's date.c needs strptime).
# Usage: sh build-demo.sh linux_x64|linux_arm64|macos_x64|macos_arm64
T=$1
BIND=${2:-$1}  # optional: build with ANOTHER platform's bindings, to show what that does
case $T in
  linux_x64) ZT=x86_64-linux-gnu ;; linux_arm64) ZT=aarch64-linux-gnu ;;
  macos_x64) ZT=x86_64-macos ;; macos_arm64) ZT=aarch64-macos ;;
  *) echo "unknown target $T"; exit 2 ;;
esac
cd "$(dirname "$0")" || exit 1
ZIG=/c/user/Apps/zig-x86_64-windows-0.16.0/zig
[ -f cp.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt || exit 1
CP=$(cat cp.txt)
M2=$(cygpath -w ~/.m2/repository/org/teavm)
V=0.15.0
CLASSLIB="$M2\\teavm-classlib\\$V\\teavm-classlib-$V.jar;$M2\\teavm-interop\\$V\\teavm-interop-$V.jar;$M2\\teavm-platform\\$V\\teavm-platform-$V.jar;$M2\\teavm-core\\$V\\teavm-core-$V.jar"
B=build-$T$([ "$BIND" != "$T" ] && echo "-with-$BIND")
rm -rf "$B" && mkdir -p "$B/classes"
javac -d "$B/classes" -cp "$CP" $(find src/main/java ../gen/$BIND -name '*.java') || exit 1
java -cp "$CP;$B/classes" pocapp.BuildDriver "$B/classes" "$B/c" posixdemo.PosixDemo "$CLASSLIB" > "$B/teavm.log" 2>&1 || { tail -20 "$B/teavm.log"; exit 1; }
# macOS gaps in TeaVM's C runtime: no <uchar.h> in Apple's libc, and no POSIX real-time timers (fiber.c)
case $T in macos_*) cp teavm-uchar-darwin.h "$B/c/uchar.h" && perl darwin-fiber.pl "$B/c/fiber.c" || exit 1 ;; esac
"$ZIG" cc -target $ZT -O2 -w -Wno-error=incompatible-function-pointer-types -D_GNU_SOURCE -include "$(cygpath -w "$PWD/../posix.h")" -o "$B/posixdemo" "$B/c/all.c" > "$B/zig.log" 2>&1 || { grep -m15 error "$B/zig.log"; exit 1; }
echo "$T: built $B/posixdemo ($(wc -c < "$B/posixdemo") bytes)"
