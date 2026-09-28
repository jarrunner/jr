# Java Runner (jr) - Make Your JARs Feel Like Native Windows Executables

A tiny Windows launcher (40 KB, no runtime to install) that makes JAR files executable like native .exe files - with automatic console/GUI detection, JDK 25 AOT cache support, and simple configuration.

## What Makes jr Different?

**Make JARs truly executable without GraalVM complexity or jpackage bloat:**

1. **Execute JARs like native .exe files** - Set up Windows file association and PATHEXT, double-click JARs or run them by name from command line
2. **Automatic AOT cache (JDK 25+)** - 90% faster startup (20-30ms vs 200-300ms) with zero configuration
3. **Smart console detection** - Automatically uses java.exe (console) or javaw.exe (GUI) based on how you launch it
4. **Two modes**: Works as generic JAR launcher (no config needed) OR as dedicated app launcher with .jrc config files
5. **Your app gets its own process name** - with `jvm=dll` the JVM runs inside the launcher, so Task Manager shows `myapp.exe` instead of another anonymous `java.exe`
6. **Tiny size, nothing to install** - 40 KB with no VC++ Redistributable, vs 500 KB (Launch4j) or 50+ MB (jpackage)

**Key Features:**
- Automatic console/GUI detection (no manual configuration like Launch4j/WinRun4J)
- JDK 25 AOT cache support out of box (creates, uses, and cleans up cache automatically)
- Optional in-process JVM (`jvm=dll`) so each app is its own killable, nameable process
- Works without any config file (traditional mode) or with simple .jrc config (config mode)
- Command-line arguments override config settings
- Debug logging (opt-in only)
- Finds Java from PATH or set `java.home` (`.jrc`, or `-Xjr:java.home=` for one run) to use a specific JDK

## macOS and Linux

`posix/jrmac` does the same job there, as a shell script rather than C — same `.jrc` format, so a config written for `jr.exe` works unchanged. `posix/install.sh <name> <jar>` installs a tool. See [posix/README.md](posix/README.md), which also explains why there is no compiled binary for those platforms and does not need one.

## Download

**Pre-built executables are available in [GitHub Releases](../../releases):**

- **`jr.exe`** (40 KB) - No dependencies to install, works on any Windows 10 or later

Download, rename if desired, and start using immediately!

There is only one build. It links the Universal CRT that ships inside Windows itself, so there is no VC++ Redistributable to chase and no 200 KB static build to trade against it. On Windows 7 or 8.1 it needs the UCRT update (KB2999226); the earlier separate `jr-standalone.exe` covered that case and is no longer produced.

## Building from Source

This is the original C implementation, now under `jr_legacy_c/` (see that folder's own files and this repo's `CLAUDE.md` for the platform decision - a Java/TeaVM implementation under `jr/` is becoming the primary one; see `jr/README.md` for its own build recipe).

Build from source using Microsoft Visual C++:

```batch
cd jr_legacy_c
build-win.bat
```

**Requirements:**
- Microsoft Visual C++ build tools (portable or full Visual Studio)
- Run from "Developer Command Prompt for VS" OR have `devcmd.bat` in PATH

**Note:** For portable MSVC build tools without full Visual Studio install, see [PortableBuildTools](https://github.com/Data-Oriented-House/PortableBuildTools) (archived but functional).

This produces `jr.exe` (40 KB, no VC++ Redistributable required).

The build uses a hybrid CRT: `/MT` links vcruntime statically, while `/NODEFAULTLIB:libucrt.lib /DEFAULTLIB:ucrt.lib` swaps the bulky static Universal CRT for the copy that already lives in Windows. That is what removes the `VCRUNTIME140.dll` import without paying the 200 KB a fully static build costs. To confirm it took effect:

```batch
dumpbin /dependents jr.exe
```

Expect only `USER32.dll`, `KERNEL32.dll` and the `api-ms-win-crt-*.dll` set. A `VCRUNTIME140.dll` line means the hybrid flags were dropped and you are back to needing the redistributable.

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
[INFO] Final command: "C:\Java\jdk-25\bin\java.exe" -Djarrunner.start.micros=0 ...
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

- **Language**: C (Windows API)
- **Size**: ~20 KB
- **Dependencies**: Standard Windows libraries (kernel32.dll, user32.lib)
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
- Portable across Windows, Linux, and macOS (C code is cross-platform ready)

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
   - Passes timing data via `-Djarrunner.start.micros` and `-Djarrunner.beforejvm.micros`
   - Java code can read these properties to measure launcher overhead

7. **Execution**:
   - Constructs command: `"path\to\java.exe" [timing-props] [vm.args] [aot-cache] [java.args] [app.args] [cmdline-args]`
   - `jvm=exe` (default): runs it with `CreateProcessA()` using handle
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
- Much smaller (40 KB vs 500 KB for Launch4j), with nothing to install alongside it
- Automatic AOT cache support (90% faster startup with JDK 25+)
- Automatic console/GUI detection (no manual config needed)
- Works without config files (can also work with config when needed)
- Actively maintained (Launch4j: 2017, WinRun4J: 2018, both inactive)

**vs jpackage (bundled JRE approach):**
- 1000x smaller (doesn't bundle JRE - 40 KB vs 50+ MB)
- Users can use any Java version they want
- Easier updates (just replace JAR, no need to rebuild entire package)
- Still gets AOT performance benefits with JDK 25+

**vs GraalVM native-image:**
- No complex build process or compatibility issues
- Much faster build times (just compile C, not whole Java app)
- Smaller executables for simple use cases
- Flexibility to swap Java versions
- Works with all Java code (no reflection/JNI limitations)

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
- **No Telemetry**: No data collection or phone-home features; the only network access is the Java auto-install, and only after you say yes (see [Privacy](#privacy))
- **Source Available**: Full C source code provided for review
- **Signing**: Release builds are signed through SignPath Foundation (see [Code signing policy](#code-signing-policy)); `-Xjr:sign` signs copies you brand yourself

## Code signing policy

Free code signing provided by [SignPath.io](https://signpath.io), certificate by [SignPath Foundation](https://signpath.org).

Release binaries (`jr.exe`) are built from this repository's source by GitHub Actions ([`.github/workflows/release.yml`](.github/workflows/release.yml)) and signed only after a maintainer manually approves each signing request. Nothing built outside that pipeline is signed.

Team roles:

- Committers and reviewers: [ivan-velikanov](https://github.com/ivan-velikanov)
- Approvers: [ivan-velikanov](https://github.com/ivan-velikanov)

Changes from anyone outside the team are reviewed by a committer before they are merged.

### Privacy

This program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it. The one network feature is the Java auto-install: when no suitable JDK is found, jr asks first (console Y/n or a dialog), and only on a yes contacts the [Foojay Disco API](https://api.foojay.io) and downloads an Eclipse Temurin JDK (on Windows on ARM64, an Azul Zulu JDK where Temurin publishes no ARM64 build). It can be turned off with `java.autoinstall=false`.

## License

Apache License 2.0 - see [LICENSE](LICENSE).

## Contributing

Contributions welcome! Please ensure:
- Code follows existing style
- Test on Windows 10/11
- Update documentation for new features

## aot/ — making the cache worth having (child project)

`aot=true` in a `.jrc` tells jr to build an AOT cache, but jr cannot decide *what goes in it*: the JVM
assembles the cache from whatever the training run happened to load. A cache trained on `mytool --help` is
worse than no cache at all, because naming a cache also switches off the default CDS archive.

`aot/` is the Java side of that — a dependency-free library (`io.github.littlejlib:littlejlib-aot`) an
application uses to load itself on purpose during jr's training run. It is what jr signals with
`JR_AOT_STATE=creating`. See **[aot/README.md](aot/README.md)** for how to wire it in, how to choose what to
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
