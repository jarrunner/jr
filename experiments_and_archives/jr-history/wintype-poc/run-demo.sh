#!/bin/bash
# Runs the whole PRP-11 codegen-safety POC end to end: compiles the processor, proves it rejects
# the actual PRP-09 bugs (WPARAM/LPARAM-as-Address, a DWORD_PTR width mismatch), proves it accepts
# the corrected binding, and regenerates WinConstants.generated.java from the real bcrypt.h.
# See ../../prp/11-prp.02.codegen-safety-poc.md.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out/bad out/badwidth out/good

echo "=== 1. Compiling the annotation + processor + probe ==="
javac -d out wintype/WinType.java wintype/Address.java wintype/HeaderProbe.java wintype/WinTypeProcessor.java

echo
echo "=== 2. BadBinding.java (WPARAM/LPARAM as Address) - MUST FAIL ==="
if javac -cp out -processorpath out -processor wintype.WinTypeProcessor -d out/bad demo/BadBinding.java; then
    echo "FAIL: expected a compile error, got success"; exit 1
else
    echo "OK: rejected, as expected"
fi

echo
echo "=== 3. BadWidth.java (DWORD_PTR as int, not long) - MUST FAIL ==="
if javac -cp out -processorpath out -processor wintype.WinTypeProcessor -d out/badwidth demo/BadWidth.java; then
    echo "FAIL: expected a compile error, got success"; exit 1
else
    echo "OK: rejected, as expected"
fi

echo
echo "=== 4. GoodBinding.java (corrected types) - MUST PASS ==="
javac -cp out -processorpath out -processor wintype.WinTypeProcessor -d out/good demo/GoodBinding.java
echo "OK: compiled clean"

echo
echo "=== 5. Regenerating WinConstants.generated.java from the real bcrypt.h ==="
cd constgen
rm -rf build *.class
javac -cp "../out" -d . WinConstGen.java
java -cp ".;../out" WinConstGen
cd ..

echo
echo "=== All demonstrations passed ==="
rm -rf out constgen/build constgen/*.class
