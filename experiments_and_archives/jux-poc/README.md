juxlang proof-of-concept for PRP 10 (see ../prp/10-prp-try_juxlang.md,
../prp/10-prp.01.findings.md, and ../prp/10-prp.02.full-attempt-findings.md for the
actual findings - read both findings files, not just this one, before touching the
toolchain).

Goal: Jux (https://github.com/xdsswar/juxlang) - a new Java/C#-flavored language that
transpiles to Rust, not a JVM-bytecode compiler like TeaVM - as an alternative way to
build jr, to compare against launcher.c and the TeaVM port (../teavm-poc/).

Files here, in order of how far the port goes:
- `hello.jux` - trivial hello-world, used to validate the pipeline itself.
- `ffi_createprocess.jux` - standalone viability test for jr's single hardest Win32
  call, `CreateProcessA` with in/out structs (STARTUPINFOA/PROCESS_INFORMATION),
  before committing to a larger port. Found and worked around two real compiler bugs
  - see 01.findings.md.
- `jr.jux` (~330 lines, round 1) - the first partial port: base52 AOT cache naming,
  `.jrc` config parsing, PATH-based java resolution, console spawn + wait +
  exit-code passthrough, help text, `--create-config`.
- `ffi_funcptr.jux`, `ffi_widestring.jux`, `ffi_modulefilename.jux` (round 2,
  negative results) - confirm, respectively: `fn(...)->R` isn't parsed yet (blocks
  `jvm=dll`'s `JLI_Launch`); a hand-built UTF-16 buffer for WinHTTP-style wide-string
  calls hits raw-pointer-arithmetic bugs; raw-pointer READ-indexing (not just write)
  fails the same way (blocks `GetModuleFileNameA`-shaped APIs, and by extension the
  Java auto-installer and AOT stale-file cleanup). See 02.full-attempt-findings.md.
- `jr-full/` (round 2) - a `jux.toml` project (not just a bare `juxc` file) wrapping
  the round-1 port plus everything addable WITHOUT raw-buffer marshaling: the real
  `AttachConsole`/`FreeConsole` console-detection dance (launcher.c's actual
  `isGuiMode` logic) and opt-in file logging. This is the size-and-functionality
  CEILING reached this PRP - jvm=dll and the Java auto-installer are confirmed
  blocked, not simply left out.
- `hello-tuned/` - the same size-tuned profile applied to a bare hello-world, to
  separate "Rust/Jux runtime floor" from "this program's actual logic" in the size
  numbers (see 02.full-attempt-findings.md's floor-adjusted table).

Pre-built release binaries: `dist/jr-jux.exe` (212,992 bytes, round 1, default
profile) and `jr-full/dist/jr-jux-full.exe` (153,088 bytes, round 2, size-tuned
profile - the one to look at) - gitignored (`*.exe`, matching how `jr.exe` itself is
handled) but persist locally. Rebuild if source changes.

## Toolchain

Rust installed portably under `C:\user\Apps\cmdtools\rust\` (not on system PATH by
default - RUSTUP_HOME/CARGO_HOME point there). juxlang itself cloned+built at
`C:\user\Apps\cmdtools\rust\juxlang\`, with `juxc.exe`/`jux.exe`/`juxc-lsp.exe` copied
into `JUX_HOME = C:\user\Apps\cmdtools\rust\jux-home\`.

To get a session set up:

```powershell
$env:RUSTUP_HOME = "C:\user\Apps\cmdtools\rust\rustup"
$env:CARGO_HOME = "C:\user\Apps\cmdtools\rust\cargo"
$env:JUX_HOME = "C:\user\Apps\cmdtools\rust\jux-home"
$env:PATH = "$env:CARGO_HOME\bin;$env:JUX_HOME;$env:PATH"
```

Already-installed portable MSVC build tools (`C:\user\Apps\vsbt\`, on PATH/
INCLUDE/LIB already) are what `rustc`'s default `x86_64-pc-windows-msvc` target
links against - no separate linker setup was needed, unlike TeaVM's llvm-mingw.

## Build recipe

Round 1, a bare file (no `jux.toml`):
```powershell
juxc.exe jr.jux --run              # compile + cargo build + run, debug
juxc.exe jr.jux --build --release  # release build only
```

Round 2, the `jux.toml` project (needed for the size-tuned `[profile.release]`):
```powershell
cd jr-full
jux.exe build --release   # -> target\.rust-build\bin-jr\target\release\jr.exe
```

Verified end-to-end against `../test-scripts/TestStartupTiming.jar` and a real
installed JDK 25: `--create-config`, config-driven `vm.args`/`log.file`, AOT cache
created on first run (base52-named, independently verified byte-identical to
launcher.c's own encoding for the same file size) then correctly reused on the
second run, real `AttachConsole`/`FreeConsole` console detection, correct exit-code
passthrough. See 01.findings.md and 02.full-attempt-findings.md for the actual run
transcripts.

## If you hit a `null`-as-void* type error

`E0308: expected *mut c_void, found Option<_>` when passing a bare `null` literal
directly as a constructor or call argument for a `void*`-typed field/param is a known
compiler bug (contradicts the language spec) - bind it to a typed local first instead:

```java
void* nullPtr = null;
CreateProcessA(nullPtr, cmdLine, nullPtr, nullPtr, ...);
```

See 01.findings.md for the other round-1 bug (a negative decimal literal against a
`u32` parameter fails to typecheck - use the hex/unsigned constant instead).

## If you need to iterate a raw buffer by index

Don't - it doesn't currently compile, in any of `p[i]` (read or write) or
`*(p + i)` (arithmetic-then-deref, which lowers to pointer-to-`isize`-then-add,
losing the pointer type). See 02.full-attempt-findings.md. Stick to the
`@layout(c)` out-param struct pattern (proven, extensively used in both `jr.jux` and
`jr-full/src/main.jux`) for anything that needs to cross the FFI boundary as more
than a scalar or a `String`.

## Size-tuning a `jux.toml` project

`[profile.release]` is fully overridable - see `jr-full/jux.toml`:
```toml
[profile.release]
opt-level = "z"
lto = "fat"
panic = "abort"   # only if the program has no try/catch - see the build-system addendum
```
This took the round-1 port from 212,992 to 151,040 bytes (-29%). It is NOT a
TeaVM/LLVM-specific advantage - Rust already uses LLVM by default - most of the
remaining gap to TeaVM's numbers is the Rust/Jux runtime floor (~111,616 bytes for a
tuned bare hello-world, see `hello-tuned/`), not code-generation quality. See
02.full-attempt-findings.md's floor-adjusted comparison table.
