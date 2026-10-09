# Java Runner (jr) - Make Your JARs Feel Like Native Windows Executables

A small Windows launcher (about 610 KB, nothing to install) that makes JAR files executable like native .exe files - with automatic console/GUI detection, JDK 25 AOT cache support, Java auto-install and simple configuration. jr itself is written in Java and compiled to a native exe (see [Java compiled to a native exe](#java-compiled-to-a-native-exe)).

## What Makes jr Different?

**Make JARs truly executable without GraalVM complexity or jpackage bloat:**

1. **Execute JARs like native .exe files** - Set up Windows file association and PATHEXT, double-click JARs or run them by name from command line
2. **Automatic AOT cache (JDK 25+)** - 90% faster startup (20-30ms vs 200-300ms) with zero configuration
3. **Smart console detection** - Automatically uses java.exe (console) or javaw.exe (GUI) based on how you launch it
4. **Two modes**: Works as generic JAR launcher (no config needed) OR as dedicated app launcher with .jrc config files
5. **Your app gets its own process name** - with `jvm=dll` the JVM runs inside the launcher, so Task Manager shows `myapp.exe` instead of another anonymous `java.exe`
6. **Small, nothing to install** - about 610 KB (about 160 KB if you pack it with UPX, see [docs/upx.md](docs/upx.md)), no VC++ Redistributable, no bundled JRE, vs 50+ MB (jpackage)

**Key Features:**
- Automatic console/GUI detection (no manual configuration like Launch4j/WinRun4J)
- JDK 25 AOT cache support out of box (creates, uses, and cleans up cache automatically)
- Optional in-process JVM (`jvm=dll`) so each app is its own killable, nameable process
- Works without any config file (traditional mode) or with simple .jrc config (config mode)
- Command-line arguments override config settings
- Debug logging (opt-in only)
- Finds Java from PATH or set `java.home` (`.jrc`, or `-Xjr:java.home=` for one run) to use a specific JDK

## macOS and Linux

The Java code that makes up jr also compiles for POSIX systems. The Linux build runs end to end (tested under WSL, with a real JDK), and a macOS build links but has not yet run on a Mac. Neither is released yet: making jr work properly on the Mac is the current focus (see [What comes next](#what-comes-next)). Linux is built and tested mainly because it is the closest thing to a Mac that can be run on the development machine. The `.jrc` format is the same on every platform.

## Download

**Pre-built executables are in [GitHub Releases](../../releases):**

- **`jr.exe`** (about 610 KB) - Windows x64. Nothing to install: it needs only DLLs that ship with Windows.
- **`jr-windows-arm64.exe`** - Windows on ARM64. Built and linked for ARM64, not yet run on ARM64 hardware.
- **`jr-noicon-*.exe`** - the same without jr's icon, for tools that stamp their own (this is what `jr-maven-plugin` bundles).
- **`jr-macos`** - macOS, one binary for Apple Silicon and Intel (also as `jr-macos-arm64` and `jr-macos-x86_64`), signed ad hoc. Install with `curl -fsSL https://github.com/jarrunner/jr/releases/latest/download/install.sh | sh`; see [docs/macos.md](docs/macos.md).
- **`SHA256SUMS`** - check a download against it. The release binaries are not code-signed.

Download, rename if desired, and start using immediately! Tested on Windows 11.

## Related repositories

- [jr-maven-plugin](https://github.com/jarrunner/jr-maven-plugin) builds a jr launcher for your own app in its Maven build: your icon, your version, your config baked in. It bundles the jr binaries of a jr release.
- [jr-runtime](https://github.com/jarrunner/jr-runtime) is an optional library for an app launched by jr: typed access to what jr passes it, and an update check.

## Building from Source

`mvn package` in this folder, needing JDK 25, Maven and [llvm-mingw](https://github.com/mstorsjo/llvm-mingw/releases) (the `msvcrt` build) on PATH. Details are in [docs/development.md](docs/development.md). The release workflow (`.github/workflows/release.yml`) runs the same Maven build on a clean Windows machine, so a release contains nothing built on a developer's machine.

jr began as a hand-written C launcher. That version lives on in its own repository, [jarrunner/jr_legacy_c](https://github.com/jarrunner/jr_legacy_c), but it has far fewer features than this one and is no longer developed.

## Java compiled to a native exe

jr is written in Java. [TeaVM](https://teavm.org) compiles it to C, and an LLVM toolchain compiles that C to a native exe: llvm-mingw on Windows (x64 and ARM64), zig cc on Linux and macOS. The Windows API bindings (functions, constants, struct layouts) are not written by hand: [jextract-teavm](https://github.com/jarrunner/jextract/tree/teavm/teavm) generates them from the real system headers, and the C compiler checks every size and offset again on each build.

The result, measured rather than claimed:

- **On Windows it is a real, shipping native program.** jr runs real tools every day. It has a GUI error dialog, progress windows, an HTTPS downloader, resource editing, Authenticode signing and an in-process JVM, all in Java, in about 610 KB.
- **Size.** A hand-written C launcher would be smaller, but less so than you might think. When the Java port reached feature parity with the old C launcher it was about 4.8x its size (370 KB against 77 KB). Most of that is a fixed cost: TeaVM's runtime is paid for once. Packed with UPX, today's 610 KB exe is about 160 KB, roughly what a C launcher with today's features would be, by our estimate. See [docs/upx.md](docs/upx.md) for the numbers.
- **Speed.** A jr launch costs about 30 ms on its own. Next to starting a JVM that is noise.
- **Cross-platform.** Linux builds and runs. macOS builds, but has not yet run on a Mac. So for now the honest claim is: proven on Windows, working on Linux, macOS next.

### What the code looks like

To a Java developer who already knows the OS functions they are calling, it reads as ordinary modern Java (`var`, lambdas, switch expressions, small final classes) with native calls that look like static methods. About a third of the source files contain no native code at all. A typical native piece, shortened from `Crc32.java`:

```java
var crc32 = (RtlComputeCrc32Fn) (Object) fn;      // a function pointer from GetProcAddress
return memScoped(() -> {                           // native memory, freed when the block ends
    var f = WinApi.fopen(cstr(path), cstr("rb"));
    var buf = alloc(READ_CHUNK);
    var crc = 0;
    long n;
    while ((n = WinApi.fread(buf, 1, READ_CHUNK, f)) > 0) {
        crc = crc32.invoke(crc, buf, (int) n);
    }
    WinApi.fclose(f);
    return hex(crc);
});
```

What will feel unfamiliar, in rough order:

1. **Some everyday JDK calls are avoided on purpose.** TeaVM compiles in everything a call can reach, so `String.format` costs about 580 KB, and `String.split` or `toLowerCase` pull in the regex engine and Unicode tables. jr uses small hand-written helpers instead. This surprises Java developers more than anything native does.
2. **C structs are read through generated accessors by offset**, for example `WinOffsets.FILETIME.dwLowDateTime(addr)`. The offsets are checked against the real compiler, so this is safe, but it reads more like C than Java.
3. **Function pointers and callbacks** are small abstract classes extending TeaVM's `Function`, and a function pointer is cast to one.
4. **No reflection**, and C structs cannot be passed by value (a header macro is the workaround).
5. **Threads are green threads.** `java.lang.Thread`, `synchronized`, `join` and `wait`/`notify` work, but TeaVM runs every Java thread as a fiber on one OS thread, switching only where a thread waits (`sleep`, `wait`, `join`). A thread that computes without waiting is never interrupted, and a blocking OS call pauses all of them. There is no parallel Java code, and therefore no data races in it. Real OS threads are fine for native work, but Java code must not run on them, because TeaVM's runtime (its garbage collector included) is not thread-safe. jr itself starts no threads.

The rules for native memory are short and written at the top of `N.java`: everything is allocated outside the garbage-collected heap, and a pointer never outlives its `memScoped` block.

### What comes next

Planned, not done:

- **macOS**: run and test on real Macs, then release it.
- **The POSIX launcher catches up** with the Windows one, using the same generated-binding approach.
- **Windows on ARM64**: run on real hardware.
- **Fixes in the bindings generator**, so that the code needs fewer workarounds.

The Windows feature set is essentially complete. Size is watched carefully, but it is no longer the main goal.

## Quick Start - Make JARs Executable System-Wide

The most powerful way to use jr is to make ALL jar files on your system executable like native .exe files:

### Step 1: Set Up File Association

Associate `.jar` files with `jr.exe`:

```batch
# Windows Registry approach (run as Administrator)
ftype jarfile="C:\path\to\jr.exe" "%1" %*
assoc .jar=jarfile
```

Or use Windows Settings:
- Right-click any `.jar` file → Open with → Choose another app
- Browse to `jr.exe` → Check "Always use this app" → OK

### Step 2: Add .JAR to PATHEXT (Optional but Recommended)

This allows running JARs by name without typing `.jar` extension:

```batch
# Add to System Environment Variables
setx PATHEXT "%PATHEXT%;.JAR"
```

Or manually:
- Open System Properties → Environment Variables
- Edit `PATHEXT` variable
- Append `;.JAR` to the end
- Original: `.COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC`
- Updated: `.COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC;.JAR`

**What this gives you:**

```batch
# Double-click JARs from Windows Explorer - they just work!
# (with automatic console/GUI detection and AOT caching)

# Run JARs from command line by name (if in PATH)
myapp.jar arg1 arg2

# Or even without .jar extension
myapp arg1 arg2

# Works everywhere, with automatic AOT speedup (JDK 25+)
```

**No more fragile batch files!** Your JARs become first-class citizens like .exe files.

## Usage

### jr's own options: `-Xjr:`

The `.jrc` file is the place for settings. For a one-off override, jr takes options in the style of java's own `-X` options, and they must come **first**, before the jar or the app's arguments:

```batch
jr.exe [-Xjr:options] <jar-file> [app args...]
myapp.exe [-Xjr:options] [app args...]          (java.args set in myapp.jrc)
```

- `-Xjr:<key>=<value>` sets any `.jrc` key for this run, overriding the `.jrc`: `-Xjr:jvm=dll`, `-Xjr:aot=false`, `-Xjr:java.home=C:\Java\jdk-25`, `-Xjr:java.version=25+`. Quote values with spaces either way: `"-Xjr:java.home=C:\Program Files\Java\jdk-25"` or `-Xjr:java.home="C:\Program Files\Java\jdk-25"`.
- `-Xjr:yes` don't ask before auto-installing Java.
- `-Xjr:create-config[=<jar>]` write a sample `<exe>.jrc`.
- `-Xjr:help` show help.

jr reads only the leading run of `-Xjr:` tokens and stops at the first token that is not one. Everything from there on goes to the app exactly as typed, so an app's own `--yes`, `-jar` or even `-Xjr:...` argument is never taken for a jr option and never removed. An unknown `-Xjr:` option is an error, not silently ignored.

These replace the old `--jvm-dll`, `--jvm-exe`, `--enable-aot`, `--disable-aot`, `--java-home=`, `--yes` and `--create-config` flags, which were matched anywhere on the command line and collided with apps' own arguments. Passing one of them where the jar should be gives an error naming its replacement.

### Mode 1: Traditional Mode (Simple JAR Launcher)

Use `jr.exe` directly to launch any JAR file:

```batch
# Basic usage
jr.exe myapp.jar

# With arguments
jr.exe myapp.jar --arg1 value1 --arg2 value2

# Disable AOT cache
jr.exe -Xjr:aot=false myapp.jar

# Specify Java location
jr.exe -Xjr:java.home=C:\Java\jdk-21 myapp.jar

# Run the JVM inside jr.exe itself, instead of spawning java.exe
jr.exe -Xjr:jvm=dll myapp.jar

# No Java found? jr offers to download one - skip the prompt for unattended use
jr.exe -Xjr:yes myapp.jar

# Combined
jr.exe -Xjr:aot=false -Xjr:java.home=C:\Java\jdk-25 myapp.jar --verbose
```

**How it works:**
- When double-clicked from Explorer → runs with `javaw.exe` (no console)
- When run from terminal → runs with `java.exe` (console output visible)
- AOT cache enabled by default for faster subsequent launches

### Mode 2: Config Mode (With .jrc Configuration File)

For applications you run frequently, create a configuration file:

#### Step 1: Create Configuration

```batch
# Generate template config file
jr.exe -Xjr:create-config=myapp.jar

# This creates jr.jrc in the same directory
```

#### Step 2: Rename Executable (Optional)

```batch
# Rename jr.exe to match your application
copy jr.exe myapp.exe
```

#### Step 3: Customize Configuration

The generated `myapp.jrc` file (or `jr.jrc` if not renamed):

```properties
# Java Runner Configuration (.jrc format)
# Lines starting with # are comments
# Format follows WinRun4J/jpackage conventions

# VM arguments (passed before -jar, launcher auto-injects AOT flags here)
vm.args=-Xmx512m -Xms128m -Dapp.mode=production

# Java arguments (everything after VM args: -jar, -cp, class name, etc.)
java.args=-jar myapp.jar

# Application arguments (passed to your main method)
app.args=--config myconfig.xml --verbose

# AOT cache control (optional, default: true)
aot=true

# How the JVM is started (optional, default: exe)
#   exe - spawn java.exe/javaw.exe as a child process
#   dll - load jvm.dll into this process, so the app runs under this
#         executable's own name and can be killed on its own
jvm=dll

# Debug logging (optional, only used when specified)
log.file=myapp.log
log.level=info
log.overwrite=false
```

#### Step 4: Run

```batch
# Simply run the renamed executable
myapp.exe

# Command-line arguments are appended to config settings
myapp.exe --extra-arg value
```

### Configuration File Details

#### Supported Keys

| Key | Description | Example |
|-----|-------------|---------|
| `vm.args` | JVM arguments (before `-jar`) | `-Xmx512m -Xms128m -Dkey=value` |
| `java.args` | Java arguments (`-jar`, `-cp`, main class) | `-jar myapp.jar` or `-cp lib/*:app.jar com.Main` |
| `app.args` | Application arguments (after jar/class) | `--config app.xml --verbose` |
| `aot` | Enable/disable AOT cache | `true` or `false` |
| `jvm` | How the JVM is started | `exe` (default) or `dll` |
| `log.file` | Debug log file path | `myapp.log` |
| `log.level` | Log verbosity | `info`, `warning`, `error`, `none` |
| `log.overwrite` | Overwrite log on each run | `true` or `false` (default: append) |
| `java.version` | Required Java: `NN` = exactly NN, `NN+` = NN or newer (jbang's convention). A Java in PATH that doesn't match is treated as missing | `25`, `21+` (default: any Java; installs 25 if none) |
| `java.autoinstall` | Enable/disable auto-installing a missing JDK | `true` (default) or `false` |
| `java.home` | Use exactly this JDK: no PATH lookup, no version check, no install | `C:\Java\jdk-25` |

#### Complex Java Arguments Examples

**With classpath:**
```properties
java.args=-cp lib/*;app.jar com.example.Main
```

**With module system:**
```properties
java.args=-p mods -m com.example.myapp/com.example.Main
```

**Multiple JARs:**
```properties
java.args=-cp app.jar;lib/dep1.jar;lib/dep2.jar com.example.Main
```

#### Priority System

Settings are applied in this order (later overrides earlier):
1. Config file defaults
2. `.jrc` file settings
3. Command-line arguments

Example:
```properties
# In myapp.jrc
app.args=--mode production
```

```batch
# Running with additional args
myapp.exe --debug

# Final command will have: --mode production --debug
```

### Launch Mode: One Process Per App (`jvm=dll`)

By default `jr` spawns `java.exe` as a child process. That works, but every Java
application on the machine then shows up as an indistinguishable `java.exe`:

```
myapp.exe          <- the launcher, exits immediately in GUI mode
  java.exe         <- your application actually lives here
java.exe           <- somebody else's application
java.exe           <- a build tool
```

Killing one application by name (`taskkill /IM java.exe`) kills all of them.

Setting `jvm=dll` loads the JVM into the launcher process instead, so the
application *is* the executable:

```properties
jvm=dll
```

```
myapp.exe          <- the JVM and your application, one process
otherapp.exe       <- a different application, separately killable
```

Now `taskkill /IM myapp.exe` targets exactly one application, Task Manager shows
a meaningful name, and per-application firewall rules, window grouping and
process monitoring all work the way they do for native programs.

**How it works:** `java.exe` is itself a ~30 KB stub whose `main()` loads
`jli.dll` and calls `JLI_Launch()`, which loads `bin\server\jvm.dll`. In
`jvm=dll` mode `jr` does exactly the same thing from its own `main()`. Because
the real JDK launcher does the work, every `java.exe` feature is preserved:
`-jar` manifest handling (`Main-Class`, `Class-Path`), classpath wildcards,
`--module`, `@argfiles`, `JDK_JAVA_OPTIONS`, AOT cache flags, exit codes,
stdin/stdout/stderr and redirection.

**Enable it per-application** in the `.jrc`, or per-invocation on the command
line:

```batch
jr.exe -Xjr:jvm=dll myapp.jar        # force in-process
jr.exe -Xjr:jvm=exe myapp.jar        # force child process (default)
```

`-Xjr:` options override the `.jrc` setting.

**Notes:**
- `jli.dll` is located next to the `java.exe`/`javaw.exe` that was resolved from
  `PATH` or `java.home`, following symlinks (such as the Oracle `javapath`
  shim) and falling back to `%JAVA_HOME%`.
- If `jli.dll` cannot be found or loaded, `jr` logs a warning and falls back to
  spawning `java.exe`, so enabling this can not stop an application from
  starting.
- In GUI mode there is no console, so the standard streams are pointed at `NUL`.
  Unlike `javaw.exe`, `System.console()` is therefore non-null; writes to
  `System.out` succeed and are discarded rather than failing.
- The launcher no longer exits early in GUI mode - it can't, it is the
  application. This is the point of the mode, but it means the process stays in
  the process list for the application's whole lifetime.

### AOT Cache Management

**How AOT Works:**

The launcher automatically manages AOT (Ahead-of-Time) cache files for JDK 25+:

1. **First Run**: Creates AOT cache file (e.g., `myapp.g2.4ZBZgN.aot`)
   - Filename encodes JAR size and modification time
   - Takes a bit longer to start

2. **Subsequent Runs**: Reuses existing cache
   - ~90% faster startup
   - Automatic cache invalidation when JAR changes

3. **JAR Update**: Detects changes automatically
   - Old cache files are cleaned up
   - New cache created on next run

**AOT Cache Filename Format:**
```
<jarname>.<size_base52>.<modtime_base52>.aot
```

**Control AOT:**

```batch
# Disable for a single run (command-line)
jr.exe -Xjr:aot=false myapp.jar

# Disable permanently (config file)
aot=false

# Enable explicitly (config file, overrides default)
aot=true
```

### Automatic Java Installation

If no Java is found (not on PATH, no `java.home`, no matching config), or the Java on PATH doesn't satisfy the `.jrc`'s `java.version`, jr offers to download one instead of just failing:

```batch
# Normal use - asks first (console: Y/n prompt, GUI: Yes/No dialog)
jr.exe myapp.jar

# Skip the prompt (unattended/scripted use)
jr.exe -Xjr:yes myapp.jar
```

**How it works:**
1. Downloads a matching **Eclipse Temurin** build for the requested major version (default: 25), for the machine's native architecture, via the [Foojay Disco API](https://api.foojay.io) - the same API [jbang](https://www.jbang.dev/) itself uses. On Windows on ARM64 it installs a native ARM64 JDK: Temurin where one is published, otherwise **Azul Zulu** (Temurin has no Windows ARM64 build of some versions, 25 among them), and Temurin x64 under emulation only if neither exists.
2. Installs it into `%USERPROFILE%\.jbang\cache\jdks\<version>\` - **the exact same cache location jbang uses**, so the two tools share downloads. If jbang already installed that version, jr uses it directly with no download; if jr installs one first, `jbang jdk list` picks it up automatically.
3. Verifies the download's SHA256 checksum before extracting anything.
4. Extracts with the `tar.exe` already bundled with Windows (10 1803+) - no bundled archive library.
5. Shows progress matching how jr was launched: a text progress bar in console mode, a small native progress window in GUI mode.

This makes a shipped `jr.exe` + shaded jar + `.jrc` a genuinely standalone distributable - it works even on a machine with no JDK installed at all.

**Control it (`.jrc` file):**
```properties
# Required Java version (optional). Same convention as jbang's //JAVA line:
#   25   exactly 25 - Java 23 or 26 on PATH is not accepted
#   25+  25 or newer - Java 26 on PATH is accepted, 23 is not
# When the Java on PATH doesn't satisfy it, jr looks in the jbang cache first
# (for 25+, the newest cached version >= 25), and only then offers to download.
# The version is read from the JDK's own "release" file, so the check costs no
# extra JVM start. Not set: any Java on PATH is used, and 25 is installed if none.
# java.home is an explicit choice and is never version-checked.
java.version=25+

# Turn the whole feature off - fail immediately like before (optional, default: true)
java.autoinstall=false
```

### Branding a Launcher: Icon, Version Info, Signing

jr can turn a copy of itself into a branded launcher - its own icon, its own name in Task Manager, its own version, signed - with no rcedit or signtool. It uses only Windows' own machinery (the resource-update API, and `mssign32.dll`, which is what signtool itself calls), so it adds no dependency.

```batch
# A branded copy of jr.exe: icon, version info, and the name Task Manager shows
jr.exe -Xjr:make=myapp.exe -Xjr:icon=myapp.ico -Xjr:version=1.4.0.0 ^
       "-Xjr:version.FileDescription=My App" -Xjr:version.ProductName=MyApp ^
       "-Xjr:version.CompanyName=Example Ltd"

# ...and signed, with a timestamp (password comes from the environment, never the command line)
set JR_SIGN_PASSWORD=...
jr.exe -Xjr:make=myapp.exe -Xjr:icon=myapp.ico -Xjr:sign=codesign.pfx -Xjr:sign.timestamp=http://timestamp.digicert.com

# Edit an existing exe in place instead of making a copy
jr.exe -Xjr:edit=myapp.exe -Xjr:version=1.4.1.0

# See what is in an exe
jr.exe -Xjr:list-resources=myapp.exe
```

Then put `myapp.jrc` next to `myapp.exe` as usual. With `jvm=dll`, Task Manager shows the app under its own description and icon.

| Option | What it does |
|--------|--------------|
| `-Xjr:make=<out.exe>` | Copy this exe to `out.exe`, then apply the options below |
| `-Xjr:edit=<exe>` | Apply them to an existing exe, in place |
| `-Xjr:icon=<file.ico>` | Replace the main icon (the one Explorer and the taskbar show) |
| `-Xjr:version=<a.b.c.d>` | File and product version; `-Xjr:file-version=` / `-Xjr:product-version=` set one |
| `-Xjr:version.<Name>=<text>` | Any version string: `FileDescription` (Task Manager's name), `ProductName`, `CompanyName`, `LegalCopyright`, `OriginalFilename`, ... |
| `-Xjr:manifest=<file>` | The application manifest |
| `-Xjr:execution-level=<level>` | `asInvoker`, `highestAvailable` or `requireAdministrator` - changed inside the existing manifest, or a minimal one is generated |
| `-Xjr:string.<id>=<text>` | A string-table entry |
| `-Xjr:resource.<type>.<name>=<file>` | Any other resource, raw from a file. Type: a number, `RCDATA`, `HTML`, `MANIFEST`, or a custom name |
| `-Xjr:sign=<file.pfx>` | Authenticode-sign (SHA-256). Password from `JR_SIGN_PASSWORD`. The key is used in memory only and never lands in the Windows key store |
| `-Xjr:sign.thumbprint=<sha1>` | Sign with a certificate from the Windows Personal store (current user, then machine) instead - e.g. one on a hardware token |
| `-Xjr:sign.timestamp=<url>` | RFC 3161 timestamp server, so the signature outlives the certificate |
| `-Xjr:list-resources=<exe>` | Print an exe's resources |

The order is always copy, remove any old signature, resources, sign. Signing comes last because any later change to the file invalidates the signature. For the same reason, editing an already-signed exe removes its signature (and says so), rather than leaving a broken one behind. Existing resources are replaced in their own language rather than duplicated, and version fields you don't set are kept.

### Debug Logging

Logging is **opt-in only** and never happens automatically:

```properties
# Enable logging in .jrc file
log.file=myapp.log
log.level=info
log.overwrite=false
```

**Log Content:**
```
========================================
Java Runner Log - 2025-11-21 17:05:06
========================================
[INFO] Launcher started: myapp.exe
[INFO] Execution mode: Console
[INFO] Java executable: java.exe
[INFO] AOT enabled: true
[INFO] Found Java in PATH: C:\Java\jdk-25\bin\java.exe
[INFO] Using config-based mode
[INFO] Creating new AOT cache: myapp.g2.4ZBZgN.aot
[INFO] Final command: "C:\Java\jdk-25\bin\java.exe" -Dio.github.jarrunner.jr.startMicros=0 ...
[INFO] Java process started successfully (PID: 2680)
[INFO] Java process exited with code: 0
========================================
```

**Perfect for troubleshooting:**
- Configuration parsing
- Java detection
- AOT decisions
- Full command line executed
- Exit codes

## Use Cases

**Primary Use Case (Recommended):**
1. **System-wide JAR execution** - Set up file association + PATHEXT, make ALL jars executable like .exe files (with automatic AOT!)

**Other Use Cases:**
2. **Branded Application Launchers** - Rename `jr.exe` to `yourapp.exe`, add `.jrc` config, distribute together
3. **Multi-Java Environments** - Test JARs with different Java versions using `-Xjr:java.home=`
4. **Complex Launch Configurations** - Use `.jrc` files for applications requiring specific JVM settings
5. **Desktop Shortcuts** - Create shortcuts that work both ways (console and GUI)
6. **Batch Scripts** - Use in automation where you need proper exit codes
7. **Individually manageable services/apps** - Set `jvm=dll` so each app is its own named process you can kill, monitor or firewall on its own

## Testing

**Build test JAR first:**
```batch
cd test-scripts
build-test-jar.bat
```

**Run comprehensive tests:**
```batch
cd test-scripts
TestJR.bat
```

**Run the launch-mode tests** (non-interactive, builds its own test JAR, prints
PASS/FAIL per check - covers both `jvm=exe` and `jvm=dll`):
```batch
cd test-scripts
TestJvmMode.bat
```

This tests:
- Help display
- Traditional JAR launch
- Config file creation
- Config-based mode
- Logging functionality
- AOT disable flag

**Quick manual tests:**
```batch
# Test help
jr.exe

# Test traditional mode
jr.exe test-scripts\TestStartupTiming.jar

# Test config creation
jr.exe -Xjr:create-config=test-scripts\TestStartupTiming.jar

# Test config mode
copy jr.exe mytest.exe
copy test-scripts\TestStartupTiming.jar mytest.jar
# (edit mytest.jrc)
mytest.exe
```

## Technical Details

- **Language**: Java, compiled to C by TeaVM and to a native exe by llvm-mingw (see [Java compiled to a native exe](#java-compiled-to-a-native-exe))
- **Size**: about 610 KB (x64)
- **Dependencies**: only DLLs that ship with Windows (kernel32, user32, msvcrt, winhttp, bcrypt and a few more)
- **Config Format**: Simple key=value properties format with comment support
- **File Extension**: `.jrc` (Java Runner Config)
- **Behavior** (`jvm=exe`, default):
  - Console mode: Waits for process, returns exit code
  - GUI mode: Launches and exits immediately
- **Behavior** (`jvm=dll`):
  - Both modes: the JVM runs in the launcher process, which returns the
    application's exit code when the JVM is done
- **Config File Naming**: Must match executable name (e.g., `myapp.exe` → `myapp.jrc`)
- **Config Discovery**: Checks for `<exename>.jrc` in same directory as executable

## Configuration Format Compatibility

The `.jrc` format follows industry standards:
- Similar to **WinRun4J** INI format (but simplified)
- Compatible with **jpackage** launcher properties file conventions
- The same format on Windows, Linux and macOS

## How It Works

1. **Console Detection**: Uses Windows API to detect if the process has an attached console
   - `AttachConsole(ATTACH_PARENT_PROCESS)` - tries to attach to parent console
   - `GetConsoleMode()` - checks if console already exists

2. **Java Selection**:
   - Console detected → uses `java.exe`
   - No console → uses `javaw.exe`

3. **Java Location**:
   - If `java.home` is set (`.jrc` or `-Xjr:java.home=`) → uses `<java.home>\bin\java[w].exe`
   - Otherwise → searches PATH environment variable

4. **Config File Loading**:
   - Checks for `<exename>.jrc` in same directory
   - If found: parses configuration
   - If not found: falls back to traditional mode

5. **AOT Cache Management** (if enabled):
   - Calculates AOT cache filename: `<jarname>.<size_base52>.<modtime_base52>.aot`
   - Checks if cache exists for current JAR version
   - If exists: passes `-XX:AOTCache=<path>` to JVM
   - If not: passes `-XX:AOTCacheOutput=<path>` to create new cache
   - Automatically cleans up outdated AOT files from previous JAR versions

6. **Performance Timing**:
   - Records start time using high-resolution performance counter
   - Records time before JVM invocation
   - Passes `-Dio.github.jarrunner.jr.*` properties: `startMicros`, `beforeJvmMicros`, `exe` (its own path), and the whole jrc-json, one property per leaf named by its path (`app.id`, `app.version`, `update.url`, `jvm.vmArgs.0`, ...), so an app can read its own config and show its own update notice (the C launcher, `jr_legacy_c`, still passes the old `jarrunner.start.micros` / `jarrunner.beforejvm.micros`)
   - Java code can read these properties to measure launcher overhead

7. **Execution**:
   - Constructs command: `"path\to\java.exe" [timing-props] [vm.args] [aot-cache] [java.args] [app.args] [cmdline-args]`
   - `jvm=exe` (default): runs it with `CreateProcess()` using handle
     inheritance for proper I/O, waits for completion and returns the same exit
     code
   - `jvm=dll`: hands the identical command string to the JDK's own
     `JLI_CmdToArgs()` + `JLI_Launch()` in `jli.dll`, which loads
     `bin\server\jvm.dll` into this process. Same string in both modes, so
     argument parsing, quoting and precedence can not drift apart.

## Examples

### Example 1: Simple Web Server

```batch
# Create launcher
copy jr.exe webserver.exe

# Create webserver.jrc
vm.args=-Xmx1g -Xms256m
java.args=-jar webserver.jar
app.args=--port 8080 --host 0.0.0.0
aot=true
log.file=webserver.log
log.level=info

# Run
webserver.exe
```

### Example 2: Development Tool with Custom JDK

```batch
# Create launcher
copy jr.exe devtool.exe

# Create devtool.jrc
vm.args=-Xmx512m -Ddev.mode=true
java.args=-jar devtool.jar
aot=false
log.file=devtool-debug.log
log.level=info
log.overwrite=true
```

Run with custom JDK:
```batch
devtool.exe -Xjr:java.home=C:\Java\jdk-21
```

### Example 3: Classpath-Based Application

```batch
# Create myapp.jrc
vm.args=-Xmx2g
java.args=-cp lib/*;app.jar com.example.Main
app.args=--config production.xml
```

## Comparison with Other Tools

**vs Launch4j / WinRun4J:**
- One exe with nothing to install alongside it (about 610 KB, or about 160 KB packed with UPX)
- Automatic AOT cache support (90% faster startup with JDK 25+)
- Automatic console/GUI detection (no manual config needed)
- Finds or downloads a suitable Java by itself
- Works without config files (can also work with config when needed)
- Actively maintained (Launch4j: 2017, WinRun4J: 2018, both inactive)

**vs jpackage (bundled JRE approach):**
- About 100x smaller (doesn't bundle a JRE - about 610 KB vs 50+ MB)
- Users can use any Java version they want
- Easier updates (just replace the JAR, no need to rebuild the entire package)
- Still gets AOT performance benefits with JDK 25+

**vs GraalVM native-image:**
- Your app stays an ordinary jar: no native-image build, no reflection or JNI configuration
- Much faster build times (only jr itself is compiled ahead of time, once, not your app)
- Flexibility to swap Java versions
- Works with all Java code

**The Philosophy:**
jr doesn't try to hide that your app is Java. It embraces it. It just makes the execution experience feel native - double-click to run, automatic console handling, fast startup with AOT, executable from command line. Best of both worlds.

### Performance Metrics

With JDK 25+ and AOT enabled:

```
First Run (creating AOT cache):
  Startup: ~200-300ms (one-time cost)

Subsequent Runs (with AOT cache):
  Startup: ~20-30ms (90% faster!)

Without AOT:
  Startup: ~150-200ms (standard)
```

**Launcher Overhead:**
- jr overhead: ~2-3ms (measured via performance counters)
- Direct java.exe: 0ms baseline
- **Overhead is negligible** - AOT gains far outweigh it!

## Privacy & Security

- **No Embedded Paths**: Executable doesn't contain build machine paths
- **Portable**: Can be moved between directories/systems
- **No Telemetry**: No data collection or phone-home features (see [Privacy](#privacy))
- **Source Available**: The full Java source is in this repository, and release builds are made from it by GitHub Actions
- **Checksums**: Each release carries `SHA256SUMS`. The release exes are not code-signed; `-Xjr:sign` signs copies you brand yourself with your own certificate

### Privacy

This program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it. jr uses the network only for these, and only when asked to:

- **Java auto-install**: when no suitable Java is found, jr asks first (console Y/n or a dialog), and only on a yes contacts the [Foojay Disco API](https://api.foojay.io) and downloads an Eclipse Temurin JDK or JRE (on Windows on ARM64, an Azul Zulu build where Temurin publishes none). It can be turned off with `java.autoinstall=false`.
- **Downloading the app's jar**, when its config names a URL or Maven coordinates for it instead of a local file. The download is checked against the sha256 in the config.
- **Update checks and self-update**, only when run with `-Xjr:update-check` or `-Xjr:update`, from the update URL in the app's config.

## License

Apache License 2.0 - see [LICENSE](LICENSE).

## Contributing

Contributions welcome! Please ensure:
- Code follows existing style
- Test on Windows 10/11
- Update documentation for new features

## aot — making the cache worth having (companion library)

`aot=true` in a `.jrc` tells jr to build an AOT cache, but jr cannot decide *what goes in it*: the JVM
assembles the cache from whatever the training run happened to load. A cache trained on `mytool --help` is
worse than no cache at all, because naming a cache also switches off the default CDS archive.

[jarrunner/aot](https://github.com/jarrunner/aot) is the Java side of that — a dependency-free library
(`io.github.jarrunner:aot`) an application uses to load itself on purpose during jr's training run.
It is what jr signals with `JR_AOT_STATE=creating`. See **[its README](https://github.com/jarrunner/aot#readme)** for how to wire it in, how to choose what to
load (measured: bigger is *not* automatically better), how to record method profiles as well as classes
(JEP 515), and how to build the cache in the Maven lifecycle.

Two things documented there that bite in `jr` itself:

- **jr's AOT is jar-only, and that is a JVM restriction, not a jr one.** The JVM refuses to dump a cache
  when the classpath holds a non-empty directory (`Cannot have non-empty directory in paths`). So a `.jrc`
  with `aot=true` that launches `-cp target\classes;lib\*` has never had a cache. Worth surfacing in jr:
  today it silently does nothing.
- The cache is keyed on the jar's size and mtime (`<name>.<size>.<mtime>.aot`), which is why it retrains
  automatically after a rebuild.

## Support

For issues, questions, or feature requests, please file an issue in the repository.
