TeaVM proof-of-concept for PRP 07 (see ../prp/07-prp-try_teavm_tcc.md and ../prp/07-prp.02.step1-findings.md for the actual findings - read that file, not just this one, before touching the toolchain), extended in PRP 09 (../prp/09-prp-java_auto_install.md, ../prp/09-prp.02.teavm-port.md) with a java auto-install feature built in parallel with launcher.c's own version, for the same size/functionality comparison. Global TeaVM+C-backend gotchas (not specific to this project) are maintained at $claudeprompts\guidelines.teavmcpp.md - read that too before writing any new TeaVM code elsewhere.

Goal: Java -> TeaVM C backend -> a real native compiler -> native .exe, with no MSVC/GCC/JVM involved at the far end, to compare functionality and size against jr's hand-written launcher.c.

Three apps here, in order of how far the port goes:
- `Main.java` and `JrLite.java` - the original trivial hello-world and the PRP-07 minimal reference (console/GUI detection, find java.exe on PATH or via --java-home, spawn it and propagate its exit code). Superseded, moved to `../experiments_and_archives/jr-history/` - kept as historical reference, not part of this project's build.
- `jarrunner.jr.Jr` (package `jarrunner.jr`, ~55 classes) - the FULL port, feature-for-feature with launcher.c: .jrc config file, `-Xjr:<key>=<value>`/`-Xjr:yes`/`-Xjr:help`/`-Xjr:create-config[=<jar>]` command-line options (replacing the old `--flags`, which now redirect to their `-Xjr:` equivalent), java.version NN/NN+ matching against `<jdkhome>\release` with auto-install on mismatch, the AOT cache gated to JDK 25+ (base52-named, staleness cleanup), correct argument requoting for CreateProcessA (PRP-20 phase 1 - TeaVM's args[] arrives pre-split by the OS/CRT, unlike launcher.c's own re-parsed GetCommandLineA string, so a "two words"-style argument has to be requoted rather than just rejoined), full help text, java.home (.jrc key or `-Xjr:java.home=`), jvm-dll in-process mode (JLI_Launch via @Import + GetProcAddress function pointers), JR_LAUNCH_MODE/JR_AOT_* env vars, opt-in file logging with a from-scratch UTC calendar formatter, (PRP-09) auto-install of a missing JDK via the Foojay Disco API into jbang's own cache, with a console/GUI progress bar, and (PRP-20 phase 2) resedit.c's whole branded-launcher toolkit - `-Xjr:make`/`edit`/`list-resources`, icon replacement (RT_ICON/RT_GROUP_ICON rebuild), VS_VERSIONINFO (file/product version, arbitrary version strings, carrying over what a target already has), manifest/execution-level patching, the RT_STRING block format, arbitrary raw resources, PE security-directory stripping, and Authenticode signing (SHA-256, PFX or a cert-store thumbprint, optional RFC 3161 timestamp) through mssign32!SignerSignEx2 - see `EnumResourceNamesW`/`-LanguagesW`/`-TypesW`'s callbacks in `ReCallbacks.java`, jextract_teavm's "Callbacks: C calling Java". This is the one to build from now - JrLite is kept only as the smaller worked example.

Pre-built binaries of the full port (including the PRP-09 java-install feature, PRP-20 phase 1's -Xjr: options/java.version matching/AOT-JDK25 gate/argument requoting, and phase 2's resedit.c port) are kept at `dist\jr-teavm.exe` (882,688 bytes, plain `-O2`) and `dist\jr-teavm-opt.exe` (369,664 bytes, size-optimized; 1.5 KB of that since PRP-16 moved struct access onto the generated typed accessors, 4 KB since PRP-17 added native-architecture detection, 6.5 KB since PRP-18 moved every native buffer off the GC heap, ~106 KB since PRP-20 phase 2 added the whole branded-launcher/signing toolkit) so there's no need to rerun the whole pipeline just to try it - both are gitignored (`*.exe`, matching how `jr.exe` itself is handled) but persist locally. Rebuild them here if the source changes. These are ~50-60% smaller than the PRP-09 numbers (1,312,768 / 604,672) after PRP-11's size work - see `../prp/11-prp.01.size-experiments.md`: two `String.split()`/`toLowerCase()`-style calls were unconditionally dragging in java.util.regex and Unicode case-folding tables regardless of their actual (ASCII-only) arguments, plus `TeaVMOptimizationLevel.ADVANCED` (now the `BuildDriver` default) beats both `SIMPLE` and `FULL`.

## Toolchain: llvm-mingw (this is the one that works)
Installed at `C:\user\Apps\cmdtools\llvm-mingw-msvcrt-x86_64\` and its `bin\` is on the user's PATH (added 2026-09-11, checked for name collisions first - none found). Get a fresh copy from the official releases if needed: https://github.com/mstorsjo/llvm-mingw/releases - grab the `msvcrt-x86_64` asset specifically, NOT `ucrt` (msvcrt avoids a vcredist dependency, matching PRP-06's own requirement).

tcc (`C:\user\Apps\tcc-0.9.27-win64\tcc.exe`) does NOT work - hits a structural `#pragma once` bug against TeaVM's per-class header layout. Kept installed since it's tiny and the findings doc records exactly where it breaks, in case a newer tcc build is ever worth retrying.

## Build recipe
**Normally just run `powershell -File build-win.ps1`** (PRP-24). It does every step below, links both Windows architectures, and stamps the icon. Output: `dist\jr-windows-<arch>.exe` (size-optimized, the one to ship) and `dist\jr-windows-<arch>-fat.exe` (plain `-O2`, kept for comparison). `-Arch x86_64` builds one architecture, `-NoIcon` skips the icon. The llvm-mingw install carries an aarch64 target too, so the arm64 build is a real cross-compile, not a rename; check with the PE header's Machine field (0x8664 / 0xAA64). The manual steps, for when the script needs changing:

  mvn -q compile
  mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt

Resolve the four extra jars TeaVMTool's own classpath needs (teavm-classlib, teavm-interop, teavm-platform, teavm-core - see BuildDriver's javadoc for why), then generate the C:

  java -cp "%CP%;target\classes" jarrunner.jr.build.BuildDriver target\classes target\c jarrunner.jr.Jr "%CLASSLIB_JARS%"

Patch the generated tree for clang/mingw (see the findings doc and guidelines.teavmcpp.md for exactly what and why - this now also inserts `#include <Windows.h>`/`#include <time.h>`/`#include <winhttp.h>`/`#include <bcrypt.h>`/`#include <commctrl.h>`/`#include "mssign.h"` into definitions.h, and copies `bindings\mssign.h` next to `all.c`, so no `-include` compiler flags are needed any more):

  powershell -File postprocess.ps1 -Dir target\c

Compile - the java-install feature (PRP-09) needs three extra libs linked (winhttp/bcrypt/comctl32), and the resource-editing/signing port (PRP-20 phase 2) needs three more (version/crypt32/mssign32 - all six OS-provided, no vcredist implication, same reasoning as PRP-06). `-Wno-error=incompatible-function-pointer-types` is required by jextract_teavm's own callback convention (see "Callbacks: C calling Java" in `../jextract_teavm/README.md`) - clang 16+ makes that mismatch an error by default, and the ABI is the same either way:

  x86_64-w64-mingw32-clang -O2 -Wno-error=incompatible-function-pointer-types -o jr.exe target\c\all.c -lwinhttp -lbcrypt -lcomctl32 -lversion -lcrypt32 -lmssign32 -lgdi32

For a size-optimized build (matches the numbers in 09-prp.02.teavm-port.md):

  x86_64-w64-mingw32-clang -Oz -flto -ffunction-sections -fdata-sections -Wl,--gc-sections -s -Wno-error=incompatible-function-pointer-types -o jr.exe target\c\all.c -lwinhttp -lbcrypt -lcomctl32 -lversion -lcrypt32 -lmssign32 -lgdi32

### Baking in the default icon (PRP-24)
The just-linked `jr.exe` has no custom icon yet - stamp it with `../icon/jr-icon.ico` (PRP-22's final "jr" monogram, `concept-02-jr-monogram.svg`) using jr's own resource-editing feature on itself.

**The rule for any Windows .ico, settled 2026-09-29 after actually measuring it, not a guess: a single 256x256, 8-bit-per-channel, PNG-compressed entry is the best size/quality tradeoff there is - not a compromise.** Windows downscales cleanly FROM 256 for every smaller context it needs (taskbar, Explorer small/medium icons, Alt-Tab); there is no equivalent trick for upscaling a small bitmap back up for a large-icon view, so 256 is the one size that can never be missing, and every OTHER size is something Windows can already synthesize decently on its own - each one added is a few more KB of exe for not much real benefit. `jr-icon.ico` went 6 sizes -> 3 sizes -> 1 size over this project's own history as this became clear, getting smaller and no worse-looking each time (`jr-windows-x86_64.exe`: 486,912 -> 401,408 -> 387,072 bytes). A simple flat icon like this one lands around 11 KB at 256x256 8-bit; a more detailed/photographic one can run higher, but the one-size rule still holds - it's the compression budget that scales with complexity, not the case for extra sizes.

**Amended 2026-10-03 (PRP-31): the smallest sizes are the exception.** The downscale is fine for 32 px and up, but at 16-20 px (a window's caption icon; 20 at 125% scaling) a detailed or slanted design smears, and no resampling fixes it (LoadIconWithScaleDown was tried and the user could not see a difference). So `jr-icon.ico` now also carries 16, 20, 24 and 32 px entries DRAWN as pixel art (upright strokes on whole pixels, see `icon/build-ico.ps1`), about 500-800 bytes each, 13,640 bytes in all. Keep the 256 entry; add drawn small sizes only when the small icon visibly suffers. Plain PNG entries throughout, never ImageMagick's BMP-writing auto-resize.

Regenerate `jr-icon.ico` from the SVG with `../icon/build-ico.ps1` if the design ever changes - do NOT regenerate it with a plain ImageMagick `-define icon:auto-resize=...` command: that writer only PNG-compresses the largest entry and stores every smaller size as a raw uncompressed BMP, and this build of ImageMagick separately defaults to 16-bit-per-channel PNGs (double the real pixel data, no visual benefit) - together that bloated this same flat two-color icon to 110 KB before `build-ico.ps1` fixed it (see that script's own header for the measurements, and its `@(...)`-array comment for a PowerShell single-element-collapse bug that silently wrote a corrupt "0 images" header the first time a single-size icon was tried - jr's own `-Xjr:icon=` validation caught it correctly rather than stamping something broken).

A running exe cannot edit its own file (`BeginUpdateResource` fails with "Cannot open for writing (error 32)" - a sharing violation, not a bug), so a throwaway copy does the editing, targeting the real one:

  copy jr.exe jr-icon-editor.exe
  jr-icon-editor.exe -Xjr:edit=jr.exe -Xjr:icon=..\icon\jr-icon.ico
  del jr-icon-editor.exe

Check it took with `jr.exe -Xjr:list-resources=jr.exe` (expect six `ICON` entries plus one `GROUP_ICON`). This is a real edit of jr.exe's own bytes, so it must come before signing (and before packing with UPX, see `../docs/upx.md`), same as every other resource edit in this toolkit.

Verified end-to-end against real jars in `../test-scripts/` - not just "it compiles": correct help text, AOT cache auto-created on first run (matching launcher.c's base52 naming exactly), correct exit-code passthrough on both success and a real thrown exception, correct arg passthrough, and (PRP-09) a real 195MB JDK download+checksum+extract+install+launch cycle against jbang's cache layout. See the findings docs for the actual runs.

## Java auto-install (PRP-09), JRE by default (PRP-24)
If Java isn't found, offers to download a matching Eclipse Temurin build for the native architecture (Azul Zulu on ARM64 where Temurin has none - PRP-17) via the Foojay Disco API (the same one jbang uses) into `%USERPROFILE%\.jbang\cache\jdks\<version>` for a JDK, `...\<version>-jre` for a JRE - jbang's own cache layout for the JDK case, so the two tools share those downloads; the `-jre` suffix is jr's own, since jbang itself never installs a JRE. **Default is JRE, not JDK** - most `.jrc` targets only ever run java and never touch javac/jar/etc, and the JRE zip is routinely a third the size (measured: 46 MB JRE vs the historical ~190 MB JDK, both Temurin 21 x64). Set `java.type=jdk` in the `.jrc` for anything that actually needs the full JDK. A JRE request transparently reuses an already-cached JDK at the same version instead of downloading a redundant JRE (a JDK contains a full JRE); a JDK request never accepts a JRE-only cache in return (javac etc. would be missing) - verified both directions 2026-09-28, including a real end-to-end download (JRE 21, Temurin) and a real reuse (JRE request finding a pre-existing JDK-shaped cache entry and using it with no network call at all).

`.jrc` keys: `java.version=NN`, `java.autoinstall=false`, `java.type=jdk|jre` (default jre). `--yes` skips the confirmation prompt. Test-only hooks (not in `--help`): `JR_TEST_FORCE_NO_JAVA=1` (pretend nothing was found even if a real JDK is on PATH), `JR_JDK_CACHE_DIR=<path>` (redirect the install root away from the real jbang cache, for testing), `JR_ASSUME_YES=1` (same as `--yes`). See `09-prp.02.teavm-port.md` for the full implementation writeup and the two real bugs found building this (WPARAM/LPARAM typing, BCRYPT_HASH_LENGTH's actual string value).

## Splash screen (PRP-24)
No jr-side code for this - `java.awt.SplashScreen`/`-splash:<image>` is handled inside `JLI_Launch` itself (the same mechanism jpackage-generated launchers rely on), and `CmdLineBuilder.buildConfigMode` already splices `vm.args` in verbatim before `-jar`/the AOT flags, so `vm.args=-splash:myimage.png` in a `.jrc` (or `-Xjr:vm.args=-splash:myimage.png` on the command line) already works today. Verified 2026-09-28: `log.file` on a test `.jrc` showed the real assembled command (`... -Djarrunner...micros=... -splash:check-256.png -version`), and `java.exe` ran it without error. Only in `buildConfigMode` (a `.jrc` with `java.args` set) - `buildTraditionalMode` (bare `jr.exe some.jar`) never spliced any vm args at all, splash included, which is a pre-existing gap in traditional mode generally, not something new here.

Considered and rejected: jr painting its own native splash window before touching Java at all. The one gap that would genuinely justify it - first-run JDK/JRE auto-install taking a while with no JVM yet to show a Java-level splash - is already covered better by `Progress.java`'s native WinAPI progress bar (real download %, not a static image). The remaining gap (jr's own pre-launch work: reading `.jrc`, resolving the AOT cache, loading `jli.dll`) is normally sub-50ms and imperceptible, and most `.jrc`-launched targets are plain CLI tools with no window ever created, so an unconditional jr-level splash would mean flicker on every fast CLI invocation for a gap nobody can see.

The image path resolves the same way any relative java arg does (relative to jr's working directory) - for a jar that already carries its splash image, prefer the jar's own `SplashScreen-Image: <path-in-jar>` manifest header instead (shown automatically with no `.jrc` change at all, and immune to CWD).

## A single-file exe: embedded .jrc and remote run targets (PRP-24)
Two features which together let one exe carry nothing but itself.

**Embedded .jrc.** jr reads its config from its own `RCDATA` resource named `JRC`. Stamp it with the generic raw-resource option, no dedicated flag: `jr.exe -Xjr:edit=app.exe -Xjr:resource.RCDATA.JRC=app.jrc`. Since PRP-30 the embedded config wins: a `.jrc` beside an exe that has one embedded is ignored, so nobody can change a signed exe's behaviour by planting a file next to it (`-Xjr:` flags typed on the command line still apply). A key=value `<exe>.jrc` on disk is read only for an exe with nothing embedded, until the launchers in use are rebuilt with the maven plugin; then `.jrc` support goes. `-Xjr:help` and the log say which one was used ("Config: embedded in this exe").

**Remote run targets.** Instead of `java.args`, a `.jrc` can name the jar remotely:

  run.url=https://github.com/owner/repo/releases/download/v1.0/app.jar
  run.maven=com.example:app:1.0[:classifier]      (Maven Central)
  run.sha256=<64 hex characters>                  (required)

jr downloads it once, verifies it, then runs it as an ordinary `-jar <path>` (AOT caching works unchanged). The hash is required, not fetched from beside the artifact: this downloads code and runs it, and a hash from the same host proves nothing if that host is compromised. Refused, with a message and exit 1: a wrong hash (nothing is kept), no hash, a non-https URL, a malformed coordinate, both `run.url` and `run.maven`, or `run.*` together with `java.args`.

Where it goes (PRP-26). `run.maven` uses the standard `%USERPROFILE%\.m2\repository\<group>\<artifact>\<version>\` layout, so a jar Maven already downloaded is reused with no network, and jr's downloads are visible to Maven. A `localRepository` set in `settings.xml` is not read; `JR_M2_REPO` overrides the root for tests. `run.url` uses jr's own `%USERPROFILE%\.jr\cache\jars\<sha256>\` (`JR_JAR_CACHE_DIR` for tests). After jr hashes a jar once, a `<jar>.jr-sha256` sidecar records the hash and size, so a large shaded jar is not re-hashed on every launch. A replaced or truncated file fails the size check; same-size tampering is trusted, as Maven trusts its own local repository.

Interrupted downloads resume. The download goes to `<jar>.part`; the next run sends `Range: bytes=<size>-`, appends on a 206, starts over on a 200, and keeps the `.part` on any failure. A `.part` that doesn't hash correctly once finished is deleted, so the run after that starts clean. `Http.downloadToFile` now accepts only a 200/206 response, which also stops an error page being saved as a JDK zip. Verified 2026-09-29 against GitHub's CDN: resuming from a real 150 KB partial gave a verified jar, and a garbage partial was rejected and then recovered on the next run. Progress shows in the console (KB under 1 MB, else MB), and in GUI mode as a native progress window titled `<exe> - Downloading` (or `<exe> - Installing Java`), carrying the exe's own icon: whatever is stamped into it, a client's icon included. Windows scales it from the single 256 entry; the close button is greyed, because closing would not stop the download.

Still one jar, no dependencies: the target must be a self-contained (shaded) jar. Non-shaded launches with dependency resolution are planned in PRP-29. Side effect to know about: the AOT cache file (`<jar>.<hash>.aot`) is written next to the jar, so for `run.maven` it lands inside `.m2`.

Verified 2026-09-29: an exe with only an embedded `.jrc` (`run.url` pointing at picocli's GitHub release jar, plus `java.version=21`), in an otherwise empty folder, on a simulated no-Java machine with empty caches. First run downloaded and verified the jar (through GitHub's redirect to its CDN), installed a Temurin JRE 21, and ran the app. Second run took 0.77 s with no network. Maven Central was checked with `org.slf4j:slf4j-api:2.0.13`, including a classifier.

Not done, by the PRP's own call: putting the shaded jar itself inside the exe.

## The config as JSON: jrc-json (PRP-30)

An embedded config whose first character is `{` (after an optional UTF-8 BOM) is read as JSON; anything else is the key=value `.jrc` above. A jrc-json is only ever read from inside the exe: one found as a file on disk is refused with a message saying how to bake it in. The JSON maps onto the same settings:

```json
{
  "app":    { "id": "io.github.example:demo", "version": "1.0", "args": ["hello world", "second"] },
  "java":   { "version": "21+", "type": "jre", "home": "C:\\jdk-25", "autoinstall": true },
  "jar":    { "sha256": "<64 hex>", "sources": [ { "maven": "g:a:v" } ] },
  "jvm":    { "mode": "dll", "vmArgs": ["-Xmx256m", "-Dgreeting=two words"], "javaArgs": "-cp lib/* com.example.Main" },
  "aot":    true,
  "log":    { "file": "app.log", "level": "info", "overwrite": false },
  "update": { "url": "https://example.org/demo/update.json", "channel": "stable" }
}
```

- `jar.sources` entries carry exactly one of `maven` (`g:a:v`), `url` (https only) or `path`. A `path` may use `%VAR%` and is taken relative to the exe's folder when not absolute; `maven` and `url` need `jar.sha256`. They are tried in order: the first `path` whose file exists runs as it is, with no network; otherwise each `maven` / `url` entry in turn until one gives a jar matching `jar.sha256` (an unreachable host or a wrong file moves on to the next). Mirrors of the same file share a cache slot, so a jar fetched from any of them is reused offline. When every source fails, the last reason is shown.
- A downloaded jar is checked by SHA-256 once, when it arrives. On every later run, `jar.verify` decides: `"crc32"` (default) reads the whole jar for its CRC32 and compares it with `jar.crc32`, which the maven plugin bakes into the exe, so the expected value cannot be changed without changing the exe (about 40 ms for 80 MB once the file is cached); `"sha256"` re-hashes it (about 120 ms for 80 MB, the choice when tampering matters, together with a signed exe); `"none"` reads nothing and trusts the download check plus the size. CRC32 catches any accidental or ordinary change, including a same-size one; a deliberately forged jar can be made to match a CRC32, never a SHA-256. An exe without `jar.crc32` uses the CRC32 jr records in `<jar>.jr-sha256` after the download check. The AOT cache is not checked: anything able to rewrite it could rewrite any record of it too.
- `jvm.javaArgs` is the escape hatch for launches that are not `-jar` (a classpath and a main class). Give it or `jar.sources`.
- `app.args` and `jvm.vmArgs` are lists, so an argument containing a space stays one argument.
- `app.id`, `app.version` and `update` drive `-Xjr:update-check` / `-Xjr:update` (below). The app receives the whole jrc-json as `-Dio.github.jarrunner.jr.<path>` properties, one per leaf (`app.id`, `update.url`, `jvm.vmArgs.0`, ...), plus `startMicros`, `beforeJvmMicros` and `exe` (the launcher's own path): that convention is how an app reads its own config, for example to show its own update notice.
- Booleans are JSON booleans, `java.version` is a number (`25`) or text (`"21+"`). Unknown keys are ignored, so a config written for a newer jr still runs.

**Checked before it is baked.** `-Xjr:resource.RCDATA.JRC=app.json` validates a jrc-json first: valid JSON, the right type for every key jr acts on, allowed values for `jvm.mode` and `java.type`, https addresses, a 64-hex `jar.sha256` for downloads, and something to run. Any error refuses the bake and lists every problem; unknown keys are reported as warnings. `-Xjr:check-config=app.json` runs the same check without baking. A jrc-json that fails to parse at startup stops jr with the file, line and column, instead of running with half its settings.

## Self-update: -Xjr:update-check and -Xjr:update (PRP-30)

An exe whose jrc-json has `app.version` and `update.url` can check for and install a newer version of itself, the way yt-dlp does: the whole exe is replaced, not just its jar.

The update file (one per app, at a fixed https address you control):

```json
{
  "format": 1,
  "app": "io.github.example:demo",
  "channels": { "stable": "1.0.0", "beta": "1.1.0-beta.1" },
  "releases": [
    { "version": "1.1.0-beta.1", "released": "2026-10-02T00:00:00Z",
      "exe": { "windows-x86_64": { "sha256": "<64 hex>", "urls": ["https://.../demo-windows-x86_64.exe"] } } },
    { "version": "1.0.0", "notes": "one line, shown to the user", "exe": { "...": "..." } },
    { "version": "0.9.0", "retracted": "why it was withdrawn", "exe": { "...": "..." } }
  ]
}
```

- `releases` is newest first, and that order is all jr relies on: it finds its own `app.version` by exact match and never parses version strings. `channels` names the release each channel should be on (`update.channel`, default `stable`).
- `-Xjr:update-check` prints the result. Exit 0 up to date (or this build is newer than the channel), 10 a newer version exists, 1 error. A withdrawn version is told so.
- `-Xjr:update` downloads the exe for this machine (`windows-x86_64` or `windows-aarch64`, falling back to x86_64 on ARM64), tries each https url in turn, and checks the sha256 before touching anything. It then renames the running exe to `<exe>.jr-replaced` (Windows allows renaming a running exe, not overwriting it) and moves the new one into place, putting the old one back if that fails. The next launch deletes the `.jr-replaced` file. It works even when the app's own jar is broken.
- `format` lets a future jr refuse a file it does not understand instead of misreading it.

**Worked example, live:** `examples/hello` in this repo, published at https://jarrunner.github.io/hello (repo `jarrunner/jarrunner.github.io`, GitHub Pages). Its exe, built by `jr-maven-plugin`, downloads its jar from there on first run and updates itself from `hello/update.json`; `publish.ps1` adds a build to the site. Verified 2026-10-01 from an empty folder: download the 1.0.0 exe, first run fetches and verifies the jar, `-Xjr:update-check` reports 1.1.0 (exit 10), `-Xjr:update` swaps the exe, the next launch runs 1.1.0 and removes the leftover. GitHub Pages caches files for up to 10 minutes, so a just-published release can take that long to be seen.

Also verified 2026-10-01 against a local update file and a real GitHub release asset: all channel and version cases of `-Xjr:update-check`, a full `-Xjr:update` falling through an unreachable first url, a sha256 mismatch leaving the exe byte-identical, and the leftover cleanup.

**`-Xjr:json-dump=<file>`** prints a JSON file as jr reads it, in one canonical form (compact, keys in source order, numbers as written, pure ASCII). The JVM side prints the same form, so both can be compared on the sample files in `src/test/resources/json`.

## Which Java runs, self-healing, doctor and the error dialog (PRP-31)

**Version rules.** `java.min`, `java.preferred`, `java.max` (each optional, a plain major number). `java.version=25` and `java.version=25+` both mean min 25 and preferred 25; `[21,25]` means min 21, max 25. A declared preferred is also the minimum unless a lower minimum is declared; an unset max is open. AOT is on unless `aot=false`, and it needs Java 25, so while it is on the minimum and preferred are raised to 25 (jr-maven-plugin warns). Contradictions (min above max, preferred outside the range, a max below 25 with AOT, `java.version` together with the new keys, `17.0.2`) are refused at bake time by the same check jr runs at launch. The reasoning and the prior art: `prp/31-prp.01.prior-art-and-version-rules.md`.

**Choice.** A candidate is always a Java HOME, never a bare `java.exe`: version (from `release`, else jvm.dll's version resource), launch library and architecture all come from that one folder, so a launch can no longer check one Java and load another's jli.dll. A version jr cannot read rejects the candidate. Sources: PATH (Oracle's javapath copies and Scoop shims are resolved to their homes), `JAVA_HOME` (quotes, a trailing `\bin` and a deleted folder handled), jr's/jbang's cache, the JavaSoft registry keys, the vendor folders under Program Files, `.jdks`, Scoop. Order: an installed Java of exactly the preferred version; else download it; else the nearest installed one above it, up to max; else the nearest below, down to min; else an error listing every candidate and why it was rejected. An explicit `java.home` still wins. The AOT cache name carries the JVM's identity, so a cache made by one Java is never handed to another. The POSIX build applies the same rules without the download.

**Self-healing.** When the JVM fails to start (its own error, before any app code ran) jr retries once per step: without the AOT cache, with the next suitable Java, with a download of the preferred one. In-process, jli.dll calls exit(1) itself, so jr hooks the Universal CRT's exit list and asks jvm.dll whether a JVM was ever created; an app's own failure is never retried. A refused option is named with its source (app config, command line, JDK_JAVA_OPTIONS...). Native crash logs go to `%USERPROFILE%\.jr\crash`.

**`-Xjr:doctor`** shows what a launch would do and why, changing nothing. **`-Xjr:repair`** fixes what jr made: AOT caches, a downloaded jar that no longer matches its SHA-256, damaged JDKs in its cache (folders named like `25` or `25-jre` only).

**The error dialog** (GUI mode; console mode prints the same to stderr): the problem in one or two lines, the support contact (`support.name/email/issues/url`, the plugin fills them from the pom), and an editable, redacted report. F2 copies it, F3 drafts an email, F4 opens a pre-filled GitHub issue, F5 opens the reports folder, F6 runs the doctor, F7 repairs, Esc closes. It closes itself after 35 seconds unless touched, and after 3 dialogs in 5 minutes it only saves the report, so a tool failing in a loop cannot pile them up. Every report is saved under `%USERPROFILE%\.jr\reports`. jr carries an application manifest like java.exe's (Common Controls 6, per-monitor DPI), so its windows are native and sharp, and an in-process app gets the same DPI handling it would under java.exe.

**Known limits, left as they are (2026-10-03):**
- `jvm=exe` in GUI mode: when the JVM cannot start, javaw.exe shows its own "Could not create the Java Virtual Machine" box before jr's dialog. Launching java.exe with no console window would avoid it, at the cost of the process showing as java.exe. Not worth changing while exe mode is not the main mode.
- Console mode with stderr redirected to a file or pipe: jr cannot read the JVM's error back, so a child-process start failure is reported with Java's own message and not retried. In-process runs are unaffected (the exit hook needs no text).
- macOS has never run any of this; the arm64 exe builds but has not run on ARM64 Windows.

## Tracing startup problems: JR_DEBUG (PRP-24)
`Dbg.log(...)` appends to `%TEMP%\jr-debug.log` when `JR_DEBUG=1` is set, and does nothing otherwise, so calls can stay in shipped code. It exists because `Log` (`log.file` in the .jrc) cannot see anything that happens before, or goes wrong while, the config loads. It also avoids a trap hit while building this: `System.err` output from these exes arrives on stdout, and grepping multi-line debug output hid the lines that mattered.

## WinApi's Address constants and class initialization (PRP-24)
`WinApi`'s `Address` constants (`INVALID_HANDLE_VALUE`, `RT_ICON`, `RT_RCDATA`, ...) are set in its static initializer, and that initializer does not always run before they are read. Measured: `WinApi.RT_RCDATA` read **0** inside `Config.loadEmbedded`, so its resource lookup asked Windows for type 0 and found nothing, with no error. The same read later in the run gave 10. Likely mechanism: TeaVM skips the class-init check on a static field read when the same method has already called a static method of that class, assuming the call ran the initializer, but `@Import` natives never do. The fix is the first statement of `Jr.main`, which reads `INVALID_HANDLE_VALUE` so the initializer runs before anything else. **Code that can run before `main` gets that far must not read WinApi `Address` constants.** The real fix belongs in `jextract_teavm`: have it emit these constants so they don't depend on class initialization.

## Resource editing and Authenticode signing (PRP-20 phase 2)
Ports resedit.c's whole branded-launcher toolkit onto TeaVM: `-Xjr:make=<out.exe>`/`-Xjr:edit=<exe>` (copy or edit in place), `-Xjr:list-resources=<exe>`, `-Xjr:icon=`, `-Xjr:version=`/`version.<Name>=`, `-Xjr:manifest=`/`execution-level=`, `-Xjr:string.<id>=`, `-Xjr:resource.<type>.<name>=<file>`, and `-Xjr:sign=<pfx>`/`sign.thumbprint=`/`sign.timestamp=`. Source is `Re*.java` (`ReStamp` the parsed options, `ReEntries` the pending resource-update list, `ReIcon`/`ReVersionInfo`/`ReManifest`/`ReStrings`/`ReRawResource` each resource kind, `ReApply` the BeginUpdateResource/UpdateResource/EndUpdateResource orchestration, `RePe`/`ReSign` the PE-signature strip and Authenticode signing, `ReList`/`ReRun` list-resources and the entry point) plus `ReCallbacks`, `ResId`, `ReBuf`, `ReError`, `ReVersionNumber`.

Verified parity against the real `jr.exe` (`../resedit.c`), all byte-for-byte or byte-compatible: `-Xjr:list-resources`, icon/version/manifest/execution-level/string-table/raw-resource editing (report text identical; version info additionally cross-checked with `Get-Item .VersionInfo`, Windows' own reader), signing with both a PFX and a cert-store thumbprint (cross-checked with `Get-AuthenticodeSignature` - same chain status, same subject), signature stripping on re-edit, and every error path tried (wrong PFX password, malformed thumbprint, `sign.timestamp` without `sign=`, a missing `-Xjr:list-resources` target) down to the identical Win32 error code.

Three things worth knowing for anyone extending this:
- **`bindings/windows/mssign.h`** hand-declares mssign32.dll's documented-but-unheadered structs and `SignerSignEx2`/`SignerFreeSignerContext` (the same shapes resedit.c itself hand-declares, for the same reason - no SDK ships them), so jextract_teavm can still generate and verify their offsets against this exact target rather than anyone typing them by hand. `jr.h` includes it for the generator's own parsing; `postprocess.ps1` copies it next to the real build's `all.c` and includes it there too, or `SignerSignEx2`/`SignerFreeSignerContext` are undeclared in the actual compile ("implicit function declaration") even though the generated Java binding itself compiles fine. Unlike the C original, llvm-mingw DOES ship `libmssign32.a`, so these bind as plain `@Import`s rather than needing `LoadLibrary`/`GetProcAddress`.
- **`SetFilePointerEx` cannot be bound**: it takes its distance BY VALUE (a `LARGE_INTEGER` union), and TeaVM cannot pass a struct by value in either direction (`NOT BOUND: ... passed BY VALUE`, see jextract_teavm's README "Structs by value"). `RePe.java` uses the older `SetFilePointer` (32-bit) instead - every seek here is to a PE header field or the security directory, always well under 2 GB even for a large signed exe.
- **`EnumResourceNamesW`/`-LanguagesW`/`-TypesW`'s callback parameter came out typed as plain `Address`, not a generated `Function` subclass** - on this target `ENUMRES*PROCW` resolves through mingw's `FARPROC` fallback branch, not its function-pointer one, so jextract had nothing to recognise. `ReCallbacks.java` hand-declares the three `Function` subclasses instead (their `invoke()` signature is all `Function.get` actually checks - jextract's own generated ones are no different under the hood), which is why the compile needs `-Wno-error=incompatible-function-pointer-types`.

## WinAPI bindings are generated (PRP-16)
`jarrunner.jr.WinApi` (every `@Import` native and WinAPI constant) and `jarrunner.jr.WinOffsets` (struct sizes, field offsets and typed field accessors) are GENERATED from the real mingw headers by `../jextract_teavm`. Never hand-edit them. The source of truth is `bindings/windows/winapi.symbols`, a plain list of C names: functions, constants and structs alike. To add one, add its name there and run:

  bindings\gen-bindings.cmd

This takes about 20 s. It rewrites both Java files, then has llvm-mingw's clang re-check every size, offset, width and value through `bindings/windows/verify-win.c` (a regenerated file; it names this machine's path, so do not commit it). A name that cannot be bound, for example a function that the headers declare only for a newer `_WIN32_WINNT` than mingw's default `0x601`, is printed as `NOT BOUND: <name>: <why>`, and the script exits 1. Read struct fields through the generated accessors (`WinOffsets.STARTUPINFOA.dwFlags(addr, v)`), not `addr.add(offset).getInt()`, so the width comes from the header too. One-time setup of the generator is in `../jextract_teavm/README.md`. `bindings/windows/jr.h` lists the headers the bindings are parsed against, and it must stay in step with the `#include`s that `postprocess.ps1` puts into `definitions.h`.

This replaced PRP-08's `offsetgen/` (struct offsets only) and PRP-11's `wintype-poc/` (type checking via an annotation processor), both superseded by `../prp/12-prp.01.report.md` and kept as history in `history/`.

## Native memory: always through N (PRP-18)
TeaVM's GC frees or moves any Java array known only through an `Address`, so `Address.ofData(javaArray)` passed to native code is a use-after-free waiting for the next GC (measured in `../experiments_and_archives/memsafe-lab`, write-up in `../prp/18-prp.01.lab-findings-and-api-proposal.md`). Every string, buffer, struct and out-parameter handed to WinApi therefore comes from `jarrunner.jr.N`, which allocates off the GC heap (`Arena`: one malloc'd 64 KB block plus malloc'd overflow chunks):

    import static jarrunner.jr.N.*;

    var si = alloc(WinOffsets.STARTUPINFOA.SIZE);            // zeroed struct
    WinApi.createProcessA(NULL, cstr(cmdLine), NULL, NULL, 0, 0, NULL, NULL, si, pi);
    var exitCode = intVar();                                  // out-parameter
    WinApi.getExitCodeProcess(process, exitCode);
    return exitCode.getInt();

`cstr` (ANSI) and `wcstr` (UTF-16) convert strings, `string(p)` / `string(p, max)` read them back, `alloc` / `intVar` / `longVar` / `ptrVar` give zeroed memory, and `NULL` is NULL. Memory lives for the whole program unless the code is inside `memScoped(() -> ...)`, which frees everything allocated in it on exit and fills it with 0xDD. Use `memScoped` only where memory would otherwise pile up (loops, recursion, per-log-line and per-chunk paths, big buffers), because each one costs about 340 bytes of exe. The rules: never `Address.ofData` a Java array for native code, never keep a pointer from `N` in a field or past its scope (WinAPI handles are fine), and check with `java ../experiments_and_archives/memsafe-lab/lint/AddressLint.java src/main/java`, which must report 0 and 0.

## If you hit a segfault with no compiler diagnostic
Bisect by inserting `fprintf(stderr, "CKPT n\n"); fflush(stderr);` checkpoints directly into the GENERATED `.c` file (not the Java source - that's what's actually crashing, and it's plain readable C once you're in it). This found two real bugs in this PRP in under 10 minutes each. See guidelines.teavmcpp.md for the specific bug this technique already caught (Address values in an Address[] array).
