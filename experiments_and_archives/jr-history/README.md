Superseded tools, kept readable as history. Neither is used by the build.

- `offsetgen/` (PRP-08): generated WinOffsets' struct offsets by compiling an offsetof() probe with llvm-mingw clang. It needed hand-typed field names and knew offsets only, not widths.
- `wintype-poc/` (PRP-11): an annotation processor that checked hand-written @Import widths against the headers (HeaderProbe), plus WinConstGen for bcrypt constants. It was never wired into the build.

Both were replaced by `../../jextract-teavm` (PRP-12), which generates WinApi.java and WinOffsets.java from `../bindings/winapi.symbols` via `../bindings/gen-bindings.cmd` (PRP-16). Their own relative paths (`../src/main/java/...`, `../../prp/...`) now point one level short, because they moved into history/.
