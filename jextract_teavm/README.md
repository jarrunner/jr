# jextract-teavm

TeaVM `@Import` bindings generated from real C headers, using jextract's own libclang front end. Built in PRP 12 (`../prp/12-prp-jextract_teavm_backend.md`, report `../prp/12-prp.01.report.md`).

You list names. The tool asks clang what each name is (function, constant, struct) and writes what the headers say. It never asks you for a type, a width, an offset or a value, so there is no place to type a wrong one.

```
jextract-teavm.cmd jr.h --symbols winapi.symbols -I <llvm-mingw>\include ^
    --package pocapp.jr --api-class WinApi --structs-class WinOffsets -o gen --verify-c verify-win.c
```

What comes out:
- Functions become `@Import(name = "X") public static native ...`. Pointers map to `Address`, and integers map by their width on the target (`DWORD` to `int`, `WPARAM` to `long`, `INTERNET_PORT` to `short`). The C prototype and `file:line` go in the javadoc.
- Constants use clang's evaluated value, typed by width. Pointer-typed macros like `INVALID_HANDLE_VALUE` become `Address.fromLong(..)`. Wide `L"..."` macros come out correctly: jextract itself truncates these, and `WideStrings` recovers them by asking clang for each code unit.
- Structs become `SIZE` plus field offsets, in the same shape as the old `WinOffsets` (so it is a drop-in replacement), plus typed accessors (`STARTUPINFOA.dwFlags(addr, v)`), so a wrong-width read cannot be written.
- `--verify-c` writes every size, offset, width and value back out as C `_Static_assert`s. Compile that file with a *different* compiler for the same target and headers (`clang --target=<t> -fsyntax-only`). A disagreement fails the build and names the symbol.

## Symbol file
One C name per line, `#` comments, optionally followed by the Java name: `SendMessageA sendMessageA`.
- `struct:stat stat_t` asks for the struct tag when an ordinary name shadows it (`stat` is also a function).
- A variadic function binds its fixed parameters, plus any Java types listed after the Java name: `open open3 int`. This is sound because TeaVM emits a plain C call compiled against the real prototype, so the C compiler performs the variadic convention itself. FFM cannot do this.
- A pointer-to-function typedef (`WNDENUMPROC`, `WNDPROC`) becomes a callback type (see below). Where a platform names no typedef, `qsort(4) QsortCompar` takes the type of a function's parameter, counted from 1.
- A function-like macro is `macro:NAME javaName (argTypes)`, for example `macro:MAKELONG makeLong (int, int)`. See below.
- `--symbols` may be repeated, so a platform can add lines of its own (`posix/gen-posix.sh` passes `posix.symbols` plus `posix-linux.symbols` or `posix-macos.symbols`).
- A name that cannot be bound is reported as `NOT BOUND: <name>: <why>` and the exit code is 1. It is never silently skipped. Reasons include a struct passed by value, a macro that needs an lvalue, or a name that is not declared anywhere. `windemo/run-negative.sh` checks 13 of them.

## Callbacks: C calling Java
A callback type comes out as a nested abstract class in the API class, and its `invoke` is typed from the prototype exactly as an `@Import` is:
```java
public static abstract class WNDENUMPROC extends Function {
    public abstract int invoke(Address arg0, long arg1);
}
```
Get a C function pointer to a static Java method with that signature, and pass it where the C function takes the callback. Parameters of an `@Import` that take a function pointer stay `Address`:
```java
var proc = (Address) (Object) Function.get(WNDENUMPROC.class, MyClass.class, "onWindow");
enumWindows(proc, 0L);
static int onWindow(Address hwnd, long lParam) { ... }
```
- TeaVM checks the named method against `invoke` at build time and fails the build on a mismatch, naming both signatures. So the widths go header, then `invoke`, then your method, with no step typed by hand.
- **Compile with `-Wno-error=incompatible-function-pointer-types`.** TeaVM passes the Java method's own C function (`&meth_..._onWindow`), typed with `void*` where the typedef says `HWND`. The ABI is the same. clang 16 and later makes the mismatch an error by default, and with this flag it is a warning.
- A callback runs on TeaVM's own thread, and a GC inside it is safe (`windemo` allocates 2.9 GB inside 740 callbacks and a held array survives). A callback from a thread TeaVM did not start (a thread pool, WinHTTP async) is not supported: TeaVM's runtime is single-threaded.
- `--verify-c` asserts the width of every non-pointer argument and the return type.
- jextract hands a callback's argument types over without their typedef names and without parameter names, so the javadoc shows the header's own declaration and the Java parameters are `arg0`, `arg1`. The types are exact.

## Function-like macros
libclang gives a macro no prototype, and jextract leaves function-like macros out of its model, so the symbols line gives the argument types. TeaVM emits an ordinary C call and the C preprocessor expands the macro there.
- The result type is asked of clang. A probe parse evaluates `sizeof(M(args))` and `__builtin_classify_type(M(args))`. A result type written on the line (`macro:LOWORD loword short(int)`) is checked against clang's, and a disagreement is refused.
- Argument types: `byte short char int long float double Address`.
- Refused with the reason: a name that is not a macro (a typo), an object-like macro, the wrong number of arguments, a statement-like macro (`do {} while (0)`), a struct result.
- **A macro that takes its argument's address is refused.** macOS's `WEXITSTATUS` is `(*(int *)&(x) >> 8)`. TeaVM may pass a constant (it emits `LOWORD(INT32_C(572657937))`), and `&` of a constant does not compile. Wrap such a macro in a function in a header the C build includes, and bind the function. The function must not be `static`, because jextract skips internal linkage. `posix/posix.h` does this for `jx_wexitstatus`.
- `--verify-c` asserts the result width and type class. That also fails when the macro does not exist or takes another number of arguments.

## Structs by value
TeaVM cannot pass or return a struct by value through an `@Import`. A `org.teavm.interop.Structure` is always a `void*` in its C. Tested in `windemo/src/byvalue` (`WindowFromPoint(POINT)` and `div()` returning `div_t`): clang rejects both calls. Such functions stay `NOT BOUND`.
- The way through is a one-line macro taking pointers, bound with `macro:`: `#define JX_div(n, d, out) ((void)(*(div_t *)(out) = div((n), (d))))`. See `windemo/byvalue-shim.h` and `ByValueShim`, which pass and return by value correctly. The shim header is added to the C compile with `-include`.

## Safer bindings: types, String overloads, ownership (opt-in)
Five options make the generated code carry what a compile-time checker needs (jr pairs them with `../teavm_native_check`; any other checker can read the same annotations). Every annotation and helper name is the caller's, so nothing here is specific to jr. jr's settings are in `../jr/bindings/windows/gen-bindings.cmd`.
- `--ctype-annotation NAME`: `@NAME("...")` on every pointer whose target is known, on parameters, returns, struct classes and accessors: `"struct _FILETIME"`, `"union _LARGE_INTEGER"`, `"char"` or `"wchar_t"` for text, `"int16"` to `"int64"` for an integer by width, `"pointer"` for a pointer to a pointer. `void*` and `unsigned char*` (byte buffers) stay untyped. Wide text is known by the headers' own names (`wchar_t`, `WCHAR`, `*WSTR`, `*WCH`), because in C on Windows `wchar_t` is `unsigned short`.
- `--text-converter char=FN --text-converter wchar_t=FN --text-scope ENTER,EXIT`: for each function taking read-only text, an overload taking `String` that converts with FN, calls, and frees with EXIT(ENTER()). "Read-only" is asked of clang itself in the probe parse, so `LPCWSTR` gets an overload and `LPWSTR` (an output buffer) does not. No overload when the function returns a pointer to characters, integers or pointers (`strchr`, `PathFindFileNameW`), since that could point into the freed copy. No try/finally: native calls cannot throw, and TeaVM builds try/finally on setjmp (about 400 bytes per method).
- `--buf-class NAME`: every struct accessor also takes NAME, a bounds-checked buffer type with `getX(int)` / `putX(int, x)` and `from(int)`.
- `--returned-annotation NAME`: on the struct parameter of each field-address accessor, whose result points into that parameter.
- `--escapes-annotation NAME`, with `escapes=N[,M]` after a function's Java name in the symbols file: the C function keeps parameter N (1-based) after it returns (`atexit`, `putenv`). No header says this, so the symbols file does. Such a parameter is never converted in a String overload.

## Setup (once)
1. The official jextract build is a self-contained runtime image, and it bundles libclang: https://jdk.java.net/jextract/ (Windows x64, based on JDK 25). Unpack it to `C:\user\Apps\jextract\jextract-25`, or set `JEXTRACT_HOME`.
2. jextract is not on Maven Central. To compile this project, lift its module out of the image and install it locally:
   `jimage extract --dir img %JEXTRACT_HOME%\runtime\lib\modules`, then `jar cf org.openjdk.jextract-25.2.jar -C img\org.openjdk.jextract .`, then `mvn install:install-file -Dfile=org.openjdk.jextract-25.2.jar -DgroupId=org.openjdk -DartifactId=jextract -Dversion=25.2 -Dpackaging=jar`.
3. Run `mvn package`. `jextract-teavm.cmd` runs the shaded jar on jextract's own runtime. The layout facts are read through `--add-opens`, because jextract keeps them in package-private attribute records.

## Other platforms, from this Windows machine
`posix/gen-posix.sh` generates one symbol list for Linux x64/arm64 (glibc) and macOS x64/arm64. It uses the libc headers Zig ships for cross-compiling (`zig-*/lib/libc/include`), then re-verifies each target with llvm-mingw's clang. On the same symbols the platforms really differ: `O_CREAT` 64 against 512, `st_size` at offset 48 against 96, and `mode_t` is `int` on Linux but `short` on macOS.

## Folders
- `jr/`: the proof against jr's real WinAPI surface. `derive-symbols.sh` builds the symbol list from the hand-maintained `WinApi.java`. `CompareBindings.java` diffs hand against generated. `build-app.ps1 -Variant hand|gen` builds the whole TeaVM jr on a copy of `../jr`. `e2e-test.sh` runs both builds against real jars.
- `windemo/`: callbacks and function-like macros on Windows (PRP 19). `WinDemo` calls back from user32 and the CRT into Java through the generated types and uses four macros, 8 checks (`build-windemo.sh`, then run `build-WinDemo/WinDemo.exe`, exit code = failures). `run-negative.sh` checks 13 refusals. `src/byvalue` is the struct-by-value test that fails as expected, and `ByValueShim` the macro workaround that works (`SRC=src/byvalue-shim/java GEN="gen gen-byvalue" CFLAGS="-include byvalue-shim.h" sh build-windemo.sh windemo.ByValueShim`). `src/mismatch` shows TeaVM rejecting a callback method of the wrong signature.
- `posix/`: the four non-Windows targets. `posix/demo/`: one Java program, `PosixDemo`, built from Windows for every target with TeaVM and `zig cc` (`build-demo.sh <target> [bindingsOfAnotherTarget]`). `run-in-wsl.sh` runs the Linux x64 builds on real Linux via `wsl -e`. It checks a qsort comparator callback, S_ISREG/S_ISDIR, and WIFEXITED/WEXITSTATUS (macros on Linux, wrappers in `posix.h` on macOS). It carries the two macOS fixes TeaVM's C runtime needs: `teavm-uchar-darwin.h` and `darwin-fiber.pl`.
- `upstream-fix/`: the jextract patch (wide-string macros, their charset, javadoc escaping), its standalone test, and `run-jtreg.bat` to run jextract's full suite on Windows.
- `sanity/`: the first check that stock jextract parses the mingw `windows.h`. That needs `--target=x86_64-w64-mingw32`, given to the stock CLI through `compile_flags.txt`.
