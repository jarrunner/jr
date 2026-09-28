#!/bin/sh
# Every line of negative.symbols must be refused (NOT BOUND, with its reason) and nothing may be generated.
cd "$(dirname "$0")" || exit 1
LM=$(cygpath -w /c/user/Apps/cmdtools/llvm-mingw-msvcrt-x86_64/include)
rm -rf build-negative
OUT=$(cmd //c "..\\jextract-teavm.cmd negative.h --symbols negative.symbols -I $LM --package windemo.neg -o build-negative" 2>&1)
CODE=$?
echo "$OUT" | grep "NOT BOUND"
WANT=$(grep -cv '^\s*\(#\|$\)' negative.symbols)
GOT=$(echo "$OUT" | grep -c "NOT BOUND")
if [ $CODE -eq 1 ] && [ "$GOT" -eq "$WANT" ] && [ ! -d build-negative ]; then echo "negative test: all $WANT refused, nothing generated"
else echo "negative test FAILED: exit $CODE, $GOT of $WANT refused"; exit 1; fi
