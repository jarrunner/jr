# jr-maven-plugin

Builds a [jr](../jr) launcher exe for a Java app as part of its Maven build: `io.github.jarrunner:jr-maven-plugin`, goal `jr:exe`, bound to `package` by default.

It hashes the app's shaded jar (SHA-256 and CRC32), writes the jrc-json, and has jr itself check and bake that config, the icon and the version info into a copy of the jr exe bundled in the plugin. The result is one exe that carries everything it needs: its config cannot be overridden by a file beside it, and a release exe fetches its jar on first run, verifies it, and updates itself with `-Xjr:update`.

Windows only for now: stamping uses Windows' own resource calls, so on another OS the goal logs a warning and does nothing. On Maven Central from 1.1.0 (`io.github.jarrunner:jr-maven-plugin:1.1.0`), versioned with jr: 1.1.0 bundles jr 1.1.0 (tag `v1.1.0`), built without the jr icon. To build it yourself, build jr first with `../jr/build-win.ps1 -NoIcon -DistDir dist-noicon`; the tests fail if those exes are missing.

## Use

Declare it after the shade plugin, so it hashes the jar shade just wrote:

```xml
<plugin>
  <groupId>io.github.jarrunner</groupId>
  <artifactId>jr-maven-plugin</artifactId>
  <version>1.1.0</version>
  <executions><execution><goals><goal>exe</goal></goals></execution></executions>
  <configuration>
    <icon>${project.basedir}/app.ico</icon>
    <javaVersion>25+</javaVersion>
    <jvmMode>dll</jvmMode>
    <jarUrl>https://github.com/OWNER/REPO/releases/download/v${project.version}/app.jar</jarUrl>
    <updateUrl>https://github.com/OWNER/REPO/releases/latest/download/app.update.json</updateUrl>
  </configuration>
</plugin>
```

```
mvn package                          target/jr/<name>.exe runs the jar just built (source=path)
mvn -Djr.source=url package          a release exe: fetches jarUrl on first run
mvn -Djr.installDir=<dir> package    also copies the exe to <dir>
```

`installDir` is meant to come from outside the project (`-D`, or a profile in `~/.m2/settings.xml`), never from a pom, so a personal tools folder never ends up in a public repository. An exe there that is running is renamed to `<exe>.jr-replaced` first, which jr deletes on its next start.

**A trap met in practice:** if the plugin is inherited from a parent pom, bind it to `verify` rather than `package`: an inherited plugin runs before the module's own shade plugin in the same phase, and would hash the previous jar.

## Parameters

- `jar` (`jr.jar`): the jar to launch, default `target/<finalName>.jar`.
- `name` (`jr.name`): the exe's name, default the artifactId.
- `source` (`jr.source`): where the exe finds its jar: `path` (default, the local build), `maven`, or `url`. With `maven` or `url` the exe downloads the jar and checks it against the baked SHA-256.
- `jarPath`, `mavenCoords` (default the project's own coordinates), `jarUrl`: the location for each source. `jarUrl` may contain `{sha8}`, replaced by the first 8 hex digits of the jar's SHA-256 (`app-win-{sha8}.jar` -> `app-win-a0c0b76e.jar`): every build of the jar then has its own file name, so a stale exe asks for a file that is not there instead of downloading another build and failing its check, and the release page shows which jar belongs to which exe. The release folder uses the resolved name.
- `jarVerify` (`jr.jarVerify`): the check on every run for a downloaded jar: `crc32` (default, against the CRC32 baked into the exe), `sha256`, or `none`.
- `appId` (default `groupId:artifactId`), `appVersion` (default the project version): the update feature matches on these.
- `updateUrl`, `updateChannel`: where `-Xjr:update` and `-Xjr:update-check` read the update file (format in jr's README), and which channel.
- `javaVersion` (`25`; `25` and `25+` both mean at least 25 with 25 preferred), or `javaMin` / `javaPreferred` / `javaMax` (numbers, not together with `javaVersion`); `javaType` (`jre`/`jdk`), `javaHome`, `javaAutoinstall`. jr uses an installed Java of exactly the preferred version, else downloads it, else the nearest installed one within min/max. AOT (on unless `aot` is false) needs Java 25, so it raises a lower version to 25 with a build warning. Contradictions (min above max, a max below 25 with AOT) fail the build. Rules and reasons: jr's PRP-31.
- `supportName`, `supportEmail`, `supportIssues`, `supportUrl`: who supports the app, shown in jr's error dialog (Email and Report issue buttons, which open a pre-filled mail or GitHub issue) and in `-Xjr:doctor`. Each defaults from the pom: `<organization><name>` or the first developer's name, the first developer's email, `<issueManagement><url>`, `<url>`.
- `jvmMode` (`dll` runs the JVM inside the exe, so the process carries the app's name), `vmArgs` (a list), `appArgs` (a list), `javaArgs` (instead of a jar, for a classpath launch), `aot`.
- `icon` (a 256px PNG-compressed entry; if the small caption icon looks smeared, add hand-drawn 16-32px entries case by case, see jr's README and `icon/build-ico.ps1`), `fileDescription` (what Task Manager shows, default the name), `productName`, `companyName`, `copyright`.
- `architectures`: `x86_64` (default) and/or `arm64`; with several, the exes are named `<name>-windows-<arch>.exe`.
- `outputDirectory` (default `target/jr`), `installDir` (`jr.installDir`), `skip` (`jr.skip`).

## macOS (1.2.0)

`platforms` (`jr.platforms`): `windows` (default) and/or `macos`. The Windows exes are stamped with Windows' own resource calls, so they are built only on Windows. The macOS outputs are built on any OS, Windows and CI included, and land in `target/jr/macos/`:

- `macosForms` (`jr.macosForms`): `binary` (default) and/or `app`.
  - `binary` is one file, `<name>`, with the app's config embedded in it. It copies and installs like any command-line tool. It has no icon, because macOS gives icons to bundles, not to bare binaries.
  - `app` is `<Display Name>.app` (Info.plist, the binary, the icon) plus `<name>-<version>-macos.zip` of it, which is the single file to download. The zip keeps the executable bit. With `source=path`, the jar goes inside the app (`Contents/Resources`). When the build runs on a Mac, Apple's `codesign` seals the bundle ad hoc; built elsewhere, the app runs the same but `codesign -v` reports it as unsealed.
- `macosArchitecture` (`jr.macosArchitecture`): `arm64`, `x86_64`, or `universal` (default; one fat binary for both).
- `macosIcon`: a `.png`, `.icns`, or PNG-entry `.ico`; default `icon`. `macosNoIcon` (`jr.macosNoIcon`) leaves the icon out. Inside the app, jr passes `-Xdock:name` and `-Xdock:icon` to Java, so the Dock shows the app's name and icon rather than "java".
- `macosDisplayName` (default `productName`, else `name`), `macosBundleId` (default `appId` with `:` as `.`). The app needs macOS 13 or later.
- With `source=path` and no `jarPath`, a `binary` built on a Mac runs the build's own jar, as on Windows. Built anywhere else it expects `<jar name>` beside itself.
- How the config gets in: jr's macOS build carries an empty 16 KB `__DATA,__jrc` section. The plugin writes the config there and recomputes the affected page hashes of the binary's ad-hoc signature, so the result runs on Apple silicon without a Mac in the build. An embedded config wins over any `.jrc` beside the binary, as on Windows.
- Signing: ad hoc only, with no Apple Developer ID. A copy downloaded with a browser is blocked by Gatekeeper until the user allows it (System Settings, Privacy & Security, Open Anyway). Installing with `curl` avoids that. See jr's `docs/macos.md`.
- In a release, the binary and the app's zip are published and listed in `SHA256SUMS`. They are not in the update file, because `-Xjr:update` is Windows-only.

## The release folder (1.2.0)

For a release build (`source=url`, or `release=true`) the goal also writes everything a release publishes into `releaseDirectory` (default `target/jr/release`, emptied first), so no script has to assemble it:

- the exe(s), and the jar under the file name its `jarUrl` downloads, copied from the very jar whose SHA-256 the exe carries;
- `version.txt` (the version, no newline) and any `releaseFiles` (each must exist, or the build fails naming it);
- `SHA256SUMS`, `<sha256>  <name>` per file, sorted, LF: the format `sha256sum` writes;
- `<name>.update.json`, jr's update format 1: this release first, with each exe's SHA-256 and its url (`releaseBaseUrl`, default `jarUrl`'s folder, plus the exe's name), the channel (`updateChannel`, default `stable`) set to this version, and, from `updateMergeFrom`, the channels and other releases of the previous update file. Publish it at `updateUrl`.

Parameters: `release` (`jr.release`), `releaseDirectory`, `releaseBaseUrl` (`jr.releaseBaseUrl`), `releaseFiles` (a list), `updateMergeFrom` (`jr.updateMergeFrom`).

The jrc-json it writes is kept beside the exe as `<name>.jrc.json`, for review; the exe never reads it from there.

The app receives its whole config as `-Dio.github.jarrunner.jr.<path>` properties; [jr-runtime](../jr-runtime) reads them and offers an update check.
