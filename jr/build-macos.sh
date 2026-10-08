#!/bin/sh
# Builds jarrunner.jr.PosixJr for macOS, natively ON a Mac (unlike build-posix.sh, which cross-builds
# Linux from Windows with zig):
#   stage src/main/java-posix + bindings/posix/gen/<target> + src/main/java-macos -> javac (with
#   teavm_native_check) -> TeaVM C backend (BuildDriver) -> Apple clang, with jr-posix-macos.h
#   force-included so every @Import call is prototyped.
# Before compiling, Apple clang re-checks the generated bindings' _Static_asserts against the real
# macOS SDK (they were generated from zig's bundled darwin headers).
# Size flags match the Windows build's intent (-Oz, LTO, dead code stripped, no symbols). The
# symbols are dropped at link time (-S -x) rather than by strip, and every output is signed ad hoc
# (arm64 macOS runs no unsigned arm64 code). Ad hoc is not Gatekeeper-approved: a copy a browser
# downloaded (quarantined) is refused, so install with curl or clear com.apple.quarantine.
# Targets macOS 13+, like the bindings.
# Needs: JDK 25 + mvn on PATH, Xcode or Command Line Tools.
# Usage: sh build-macos.sh [macos_arm64|macos_x64|universal]   (default: universal)
#   universal builds both and joins them with lipo into build-macos_universal/jr-macos.
cd "$(dirname "$0")" || exit 1
MINOS=13.0

build() { # $1 target dir name, $2 clang -arch
  T=$1; ARCH=$2; B=build-$T
  rm -rf "$B" && mkdir -p "$B/src" "$B/classes" || return 1

  # 1. the bindings, checked against the real SDK
  clang -arch $ARCH -fsyntax-only -w -I bindings/posix bindings/posix/verify-posix-$T.c > "$B/verify.log" 2>&1 \
    && echo "$T: bindings verified against the macOS $(xcrun --show-sdk-version 2>/dev/null) SDK" \
    || { echo "$T: binding check FAILED against the real SDK:"; grep -m20 error "$B/verify.log"; return 1; }

  # 2. sources for this target: the POSIX tree, then this target's generated bindings and the macOS
  #    overrides (src/main/java-macos, e.g. Os.java) on top of it
  cp -R src/main/java-posix/. "$B/src/"
  cp bindings/posix/gen/$T/jarrunner/jr/*.java "$B/src/jarrunner/jr/"
  cp -R src/main/java-macos/. "$B/src/"

  # 3. javac -> TeaVM C -> clang
  CHK=src/checks-off/java; [ -n "${CHECKS:-}" ] && CHK=src/checks-on/java  # PRP-35: CHECKS=1 for a bounds-checked build
  javac -d "$B/classes" -cp "$CP" -processorpath "$NC" "-Xplugin:NativeCheck $NCARGS" $(find "$B/src" $CHK -name '*.java') || return 1
  java -cp "$CP:$B/classes" jarrunner.jr.build.BuildDriver "$B/classes" "$B/c" jarrunner.jr.PosixJr "$CLASSLIB" > "$B/teavm.log" 2>&1 || { tail -30 "$B/teavm.log"; return 1; }
  # TeaVM's C runtime needs two things macOS lacks: <uchar.h> (string.c) and POSIX real-time timers
  # (fiber.c). bindings/posix/macos-shim supplies both; each header says why it is C.
  S=$PWD/bindings/posix/macos-shim
  # The empty slot jr-maven-plugin fills with an app's config (PRP-36): 16 KB of zeros as its own section, so
  # the plugin can find it by name, write the config in place and re-sign, without changing the file's layout.
  dd if=/dev/zero of="$B/jrc-slot" bs=16384 count=1 2>/dev/null
  clang -arch $ARCH -mmacosx-version-min=$MINOS -Oz -flto -w -I "$S" -include "$S/teavm-timers.h" \
      -include "$PWD/bindings/posix/jr-posix-macos.h" -Wl,-dead_strip -Wl,-S -Wl,-x \
      -Wl,-sectcreate,__DATA,__jrc,"$B/jrc-slot" \
      -o "$B/jr-posix" "$B/c/all.c" > "$B/clang.log" 2>&1 || { grep -m20 error "$B/clang.log"; return 1; }
  # the linker signs arm64 ad hoc by itself; x86_64 gets the same so both slices match
  codesign -s - -f "$B/jr-posix" 2>/dev/null || return 1
  echo "$T: built $B/jr-posix ($(wc -c < "$B/jr-posix" | tr -d ' ') bytes)"
}

# classpath, shared by every target
[ -f cp-macos.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=cp-macos.txt || exit 1
CP=$(cat cp-macos.txt)
NC=$HOME/.m2/repository/io/github/jarrunner/teavm_native_check/1.0/teavm_native_check-1.0.jar
# reinstalled whenever its sources are newer than the installed jar: the checker changes alongside jr (PRP-35)
if [ ! -f "$NC" ] || [ -n "$(find ../teavm_native_check/src ../teavm_native_check/pom.xml -newer "$NC" 2>/dev/null | head -1)" ]; then
  # with its tests: a checker mid-change elsewhere must never be installed half-written (this is the Mac's ~/.m2)
  if ! (cd ../teavm_native_check && mvn -q install > /dev/null 2>&1); then
    [ -f "$NC" ] || { echo "teavm_native_check does not build, and none is installed"; exit 1; }
    echo "warning: teavm_native_check's current sources do not build; using the installed one"
  fi
fi
NCARGS=$(sed -n 's:.*<nativecheck>\(.*\)</nativecheck>.*:\1:p' pom.xml)
M2=$HOME/.m2/repository/org/teavm
V=0.15.0
# BuildDriver splits this argument on ';' on every OS
CLASSLIB="$M2/teavm-classlib/$V/teavm-classlib-$V.jar;$M2/teavm-interop/$V/teavm-interop-$V.jar;$M2/teavm-platform/$V/teavm-platform-$V.jar;$M2/teavm-core/$V/teavm-core-$V.jar"

case "${1:-universal}" in
  macos_arm64|arm64) build macos_arm64 arm64 ;;
  macos_x64|x86_64)  build macos_x64 x86_64 ;;
  universal)
    build macos_arm64 arm64 && build macos_x64 x86_64 || exit 1
    U=build-macos_universal; rm -rf $U; mkdir -p $U
    lipo -create -output $U/jr-macos build-macos_arm64/jr-posix build-macos_x64/jr-posix || exit 1
    # lipo drops the slices' signatures; arm64 macOS refuses to run unsigned arm64 code
    codesign -s - -f $U/jr-macos 2>/dev/null || exit 1
    echo "universal: built $U/jr-macos ($(wc -c < $U/jr-macos | tr -d ' ') bytes; $(lipo -archs $U/jr-macos))" ;;
  *) echo "unknown target: $1"; exit 2 ;;
esac
