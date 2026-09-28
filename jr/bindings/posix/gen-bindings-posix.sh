#!/bin/sh
# jr's real POSIX bindings (not the toy demo's under ../../../jextract_teavm/posix) - mirrors that
# demo's gen-posix.sh recipe exactly: one symbol list (posix.symbols), clang parses each target's
# REAL libc headers (Zig's bundled cross-compile sysroots) so every width/offset/constant is that
# platform's own, then a DIFFERENT compiler (llvm-mingw's clang) independently re-checks the
# generated _Static_asserts.
# Output: gen/<target>/littlejlib/jr/{PosixApi,PosixOffsets}.java - same package as the Windows
# WinApi/WinOffsets, so PosixJr.java and its POSIX-specific helper classes compile unchanged
# against whichever target's generated files are on the classpath. Only linux_x64's output is
# currently adopted into ../../src/main/java-posix - see PRP-21's status file.
cd "$(dirname "$0")" || exit 1
Z=$(cygpath -w /c/user/Apps/zig-x86_64-windows-0.16.0/lib/libc/include)
run() { # $1 target dir name, $2 clang triple, rest: clang include/define args
  pkg=$1; triple=$2; shift 2
  hdr=jr-posix.h; extra=""
  case $pkg in macos_*) hdr=jr-posix-macos.h; extra="--symbols posix-macos.symbols" ;; esac
  echo "== $triple"
  cmd //c "..\\..\\..\\jextract_teavm\\jextract-teavm.cmd $hdr --symbols posix.symbols $extra --target $triple $* --package littlejlib.jr --api-class PosixApi --structs-class PosixOffsets -o gen/$pkg --diagnostics $pkg-clang.log --verify-c verify-posix-$pkg.c" 2>&1 | grep -v '^wrote gen'
  if clang --target="$triple" -fsyntax-only -w "$@" "verify-posix-$pkg.c" > "verify-posix-$pkg.log" 2>&1
  then echo "   independent check (clang $(clang -dumpversion) --target=$triple): all assertions hold"
  else echo "   independent check FAILED:"; grep -E "error" "verify-posix-$pkg.log" | head -10
  fi
}
run linux_x64   x86_64-linux-gnu  -D__GLIBC_MINOR__=39 -I "$Z\\x86-linux-gnu" -I "$Z\\generic-glibc" -I "$Z\\x86-linux-any" -I "$Z\\any-linux-any"
run linux_arm64 aarch64-linux-gnu -D__GLIBC_MINOR__=39 -I "$Z\\aarch64-linux-gnu" -I "$Z\\generic-glibc" -I "$Z\\aarch64-linux-any" -I "$Z\\any-linux-any"
run macos_x64   x86_64-apple-macos13 -I "$Z\\any-darwin-any"
run macos_arm64 aarch64-apple-macos13 -I "$Z\\any-darwin-any"
