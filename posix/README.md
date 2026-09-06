# jrmac — the POSIX half of `jr`

`jr`'s idea — a jar behaves like a native binary, with JDK 25's AOT cache managed for you — on macOS and Linux, as a shell script instead of `jr`'s Windows C.

No build step. `jrmac` is the whole tool.

The name is historical; it was written for a Mac and nothing in it is macOS-specific. It is deliberately not renamed, because every installed tool is a symlink pointing at that filename.

## Install a tool

```
./install.sh mytool ~/my-tools/jars/mytool.jar
```

That is it. `mytool ...` now runs the jar under its own name. The first run trains an AOT cache beside the jar; every run after reuses it; a jar rebuild (newer mtime than the cache) retrains automatically.

`install.sh <name> <jar> [--main CLASS] [--no-aot] [--bin DIR] [--force]`. It defaults to `~/my-tools/bin`, writes the `.jrc` for you, and refuses to overwrite an existing tool unless you pass `--force`.

By hand it is two steps, and both have to be right:

```
ln -s /path/to/jrmac ~/my-tools/bin/mytool
cat > ~/my-tools/bin/mytool.jrc <<'EOF'
jar=../jars/mytool.jar
aot=true
EOF
```

## A symlink, never a copy

One file then carries every fix to every tool at once. Copies drift silently, and the one left behind is found months later when a bug that was fixed long ago comes back on one tool only.

This is not hypothetical: `cp` on an already-installed tool **follows the link and gives you a copy**, so the obvious way to add a second tool — copy the first one and edit — produces exactly the stale file this warns about. `install.sh` is there so that is not the obvious way. It also names the problem when it finds one, and `--force` converts a stale copy back into a symlink.

## `.jrc` keys

Three, and anything else is ignored. The format is `jr`'s, so a `.jrc` written on Windows works here unchanged.

- `jar=<path>` — required. A relative path resolves against the `.jrc`'s own directory.
- `main=<class>` — optional. Runs `java -cp jar main` instead of `java -jar jar`.
- `aot=true|false` — default `true`. Set `false` for a daemon or UI process that runs until it is killed rather than exiting: the AOT cache is written at normal JVM exit, so it never captures anything from a process that never gets there.

## Each tool is its own named process

`jrmac` ends in `exec -a "$NAME" java …`, which sets argv[0] to the tool's name. `ps` and Activity Monitor report argv[0], so the tool shows as `mytool` rather than as `java -jar /Users/…/mytool.jar`.

Without it, a machine running six of these shows six identical anonymous JVMs, and "which one is which" has no answer. It costs one flag. It changes only argv[0] and not the executable the kernel runs, so `java.home` still resolves off the real binary.

On Windows `jr` buys the same thing the expensive way, with `jvm=dll` running the JVM inside the launcher process so Task Manager shows the app's name.

## Why there is no compiled Mac binary

`jr`'s C source is ~1500 lines of Windows API: `CreateProcess`, console attach and detach to pick `java.exe` over `javaw.exe`, and the optional in-process JVM through `jli.dll`. None of those are problems that exist here — there is no console/GUI executable split to fake, and the process-name question is answered above by one `exec` flag.

What is actually worth having is JDK 25's AOT cache, and that is two `java` command-line flags rather than launcher machinery. A shell script gets the identical speedup with nothing to port and nothing to build.

A real Mach-O binary would add an icon and a `.app` bundle, and with them `Info.plist`, codesigning and notarization. If a tool ever needs to be double-clicked from Finder rather than typed, that is the point to reconsider — not before.

## Requirements

`bash` and a `java` on `PATH` (JDK 25 or later for the AOT cache; older JDKs work with `aot=false`). macOS ships bash 3.2, which is supported — see the empty-array note in the script, which is not a style choice.
