#!/bin/bash
# PRP-42: builds a THROWAWAY jr for the tests in this folder: its Os reads the __jrc section from /tmp/jr-test.slot when
# that file exists (so a test can give it any config and bundle files without jr-maven-plugin), and app bundles are on
# (so -Xjr:install runs on Linux too). Never ship it. The source patch is reverted before this exits, whatever happens.
# Run from jr's root. $1 = where to copy the test binary; the rest = extra Maven arguments
# (from Windows for Linux: -Dlinux -Djr.zig=<zig.exe>; on a Mac: none).
set -u
OUT="$1"; shift
case "$(uname -s)" in Darwin) F=src/main/java-macos/jarrunner/jr/Os.java; BIN=jr-macos-$(uname -m | sed s/aarch64/arm64/) ;;
                      *) F=src/main/java-linux/jarrunner/jr/Os.java; BIN=jr-linux-x86_64 ;; esac
ORIG=$(mktemp)
cp "$F" "$ORIG"
trap 'cp "$ORIG" "$F"; rm -f "$ORIG"; echo "test patch lines left in $F: $(grep -c "TEST ONLY" "$F")"' EXIT
perl -0pi -e '
s/static Buf embeddedSlot\(\) \{\n/static Buf embeddedSlot() { \/\/ PRP-42 TEST ONLY\n        var testSlot = testSlot();\n        if (testSlot != null) return testSlot;\n/;
s/static String embeddedConfig\(\) \{\n        return null;\n    \}/static String embeddedConfig() { \/\/ PRP-42 TEST ONLY\n        var s = embeddedSlot();\n        return s == null ? null : string(s.ptr(), s.size());\n    }/;
s/static final boolean APP_BUNDLES = false;/static final boolean APP_BUNDLES = true; \/\/ PRP-42 TEST ONLY/;
s/\n\}\s*\z/\n\n    \@Unsafe("PRP-42 TEST ONLY: reads \/tmp\/jr-test.slot into a buffer of its own size")\n    static Buf testSlot() {\n        var p = "\/tmp\/jr-test.slot";\n        var n = (int) FileInfo.size(p);\n        if (n <= 0) return null;\n        var b = Buf.alloc(n);\n        var f = PosixApi.fopen(p, "rb");\n        if (f.toLong() == 0) return null;\n        PosixApi.fread(b.ptr(), 1, n, f);\n        PosixApi.fclose(f);\n        return b;\n    }\n}\n/;
' "$F"
grep -q "static Buf testSlot" "$F" && grep -q "if (testSlot != null)" "$F" || { echo "the test patch did not apply to $F"; exit 1; }
mvn -B -q package -Djr.dist=target/test-build "$@" 2>&1 | grep -E "ERROR|error:" | head -20
cp "target/test-build/$BIN" "$OUT" && chmod 755 "$OUT" && echo "test build: $OUT"
