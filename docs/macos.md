# jr on macOS

## Install

```sh
curl -fsSL https://github.com/jarrunner/jr/releases/latest/download/install.sh | sh
```

This installs `jr` into `~/.local/bin`. It is one universal binary that runs on both Apple silicon and Intel Macs, needs macOS 13 or later, and needs no admin rights. The installer checks the binary against the release's `SHA256SUMS` and refuses to install a file that doesn't match. `JR_VERSION=1.2.0` installs a specific release; `JR_INSTALL_DIR=...` installs somewhere else.

Use the installer (or `curl`) rather than a browser download. jr is signed ad hoc, not with an Apple Developer ID. macOS refuses a file like that once a browser has marked it as downloaded, and it keeps refusing that file even after the mark is removed; the launch just hangs on a dialog. `curl` sets no mark. If you did download a copy with a browser, run `xattr -d com.apple.quarantine jr` on it before running it the first time.

## Use

Same as on Linux: copy `jr` to the name of your app and put `<name>.jrc` beside it:

```sh
cp ~/.local/bin/jr myapp
printf 'java.args=-jar /path/to/myapp.jar\n' > myapp.jrc
./myapp args...
```

The release assets are `jr-macos` (universal), `jr-macos-arm64` and `jr-macos-x86_64` (thin), and `install.sh`.

## Making a macOS app with jr-maven-plugin

Add `macos` to the plugin's `platforms` to get, from the same `mvn package` (on Windows too): a single-file binary with the app's config embedded, and/or an `.app` with its icon plus a zip of it to download. You choose arm64, x86_64 or universal. Details are in the plugin's README, under "macOS".

## Build

On a Mac with Xcode or the Command Line Tools, JDK 25 and Maven:

```sh
cd jr && sh build-macos.sh            # universal: build-macos_universal/jr-macos
sh build-macos.sh macos_arm64          # or one architecture
```

The script checks the generated bindings against the real macOS SDK, compiles the POSIX sources with the macOS overrides from `src/main/java-macos/` (javac, then TeaVM, then Apple clang), and signs every output ad hoc. `bindings/posix/macos-shim/` fills two gaps in TeaVM's C runtime on macOS (`<uchar.h>` and POSIX timers); each header there explains itself.

From a Windows checkout, `powershell -File jr\sync-to-mac.ps1 -Build` mirrors the working tree to a Mac reachable as `ssh mac` (including unpushed commits and uncommitted changes) and builds it there.

Releases build the macOS binaries on GitHub's `macos-latest` runner (`.github/workflows/release.yml`, job `macos`).
