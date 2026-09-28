#!/bin/sh
# One symbol list, four targets, all generated on this Windows machine: clang parses each platform's REAL libc
# headers (the sets Zig ships for cross-compiling) for that target triple, so every width, struct offset and
# constant is that platform's own. Output: gen/<target>/posix/{Posix,PosixStructs}.java - the SAME package in every
# target's source root, so one Java program compiles unchanged against whichever platform root it is built with.
# Then every conclusion is re-checked by a DIFFERENT compiler: the generator writes verify-<target>.c
# (_Static_asserts) and llvm-mingw's clang (a much newer clang than jextract's bundled libclang 13) compiles
# it for the same target against the same headers. Any disagreement is a compile error naming the symbol.
cd "$(dirname "$0")" || exit 1
Z=$(cygpath -w /c/user/Apps/zig-x86_64-windows-0.16.0/lib/libc/include)
run() { # $1 package suffix, $2 triple, rest: clang include/define args
  pkg=$1; triple=$2; shift 2
  echo "== $triple"
  cmd //c "..\\jextract-teavm.cmd posix.h --symbols posix.symbols --symbols posix-${pkg%_*}.symbols --target $triple $* --package posix --api-class Posix --structs-class PosixStructs -o gen/$pkg --diagnostics $pkg-clang.log --verify-c verify-$pkg.c" 2>&1 | grep -v '^wrote gen'
  if clang --target="$triple" -fsyntax-only -w "$@" "verify-$pkg.c" > "verify-$pkg.log" 2>&1
  then echo "   independent check (clang $(clang -dumpversion) --target=$triple): all assertions hold"
  else echo "   independent check FAILED:"; grep -E "error" "verify-$pkg.log" | head -10
  fi
}
run linux_x64   x86_64-linux-gnu  -D__GLIBC_MINOR__=39 -I "$Z\\x86-linux-gnu" -I "$Z\\generic-glibc" -I "$Z\\x86-linux-any" -I "$Z\\any-linux-any"
run linux_arm64 aarch64-linux-gnu -D__GLIBC_MINOR__=39 -I "$Z\\aarch64-linux-gnu" -I "$Z\\generic-glibc" -I "$Z\\aarch64-linux-any" -I "$Z\\any-linux-any"
run macos_x64   x86_64-apple-macos13 -I "$Z\\any-darwin-any"
# arm64-apple-macos13 crashed libclang 13's macro reparse once (intermittent); the aarch64 spelling is the same target.
run macos_arm64 aarch64-apple-macos13 -I "$Z\\any-darwin-any"
