#!/bin/sh
# Builds a windemo main class with TeaVM and llvm-mingw, entirely from this folder:
#   javac (demo source + gen/, the generated bindings) -> TeaVM C backend (the posix demo's BuildDriver)
#   -> jr's postprocess.ps1 (run on OUR output; jr itself is only read) -> x86_64-w64-mingw32-clang.
# -Wno-error=incompatible-function-pointer-types: TeaVM passes a callback as the Java method's own C function,
# typed with void* where the typedef says HWND etc. Same ABI; clang 16+ makes the mismatch an error by default.
# Usage: sh build-windemo.sh [mainClass]   (default windemo.WinDemo)
MAIN=${1:-windemo.WinDemo}
cd "$(dirname "$0")" || exit 1
CPF=../posix/demo/cp.txt
[ -f $CPF ] || (cd ../posix/demo && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt) || exit 1
CP=$(cat $CPF)
M2=$(cygpath -w ~/.m2/repository/org/teavm)
V=0.15.0
CLASSLIB="$M2\\teavm-classlib\\$V\\teavm-classlib-$V.jar;$M2\\teavm-interop\\$V\\teavm-interop-$V.jar;$M2\\teavm-platform\\$V\\teavm-platform-$V.jar;$M2\\teavm-core\\$V\\teavm-core-$V.jar"
B=build-${MAIN##*.}
rm -rf "$B" && mkdir -p "$B/classes"
javac -d "$B/classes" -cp "$CP" $(find src/main/java ${SRC:-} ${GEN:-gen} ../posix/demo/src/main/java/pocapp -name '*.java') || exit 1
java -cp "$CP;$B/classes" pocapp.BuildDriver "$B/classes" "$B/c" "$MAIN" "$CLASSLIB" > "$B/teavm.log" 2>&1 || { tail -20 "$B/teavm.log"; exit 1; }
grep -q "Problems:" "$B/teavm.log" && { sed -n '/Problems:/,$p' "$B/teavm.log" | head -20; exit 1; }
powershell -NoProfile -File "$(cygpath -w ../../jr/postprocess.ps1)" -Dir "$(cygpath -w "$B/c")" > "$B/postprocess.log" 2>&1 || { cat "$B/postprocess.log"; exit 1; }
x86_64-w64-mingw32-clang -O2 ${CFLAGS:-} -Wno-error=incompatible-function-pointer-types -o "$B/${MAIN##*.}.exe" "$B/c/all.c" > "$B/clang.log" 2>&1 || { grep -m15 error "$B/clang.log"; exit 1; }
echo "built $B/${MAIN##*.}.exe ($(wc -c < "$B/${MAIN##*.}.exe") bytes), clang warnings: $(grep -c 'warning:' "$B/clang.log")"
