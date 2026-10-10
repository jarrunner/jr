# Installing and updating a jr app

A jr app is one file. On Windows it is `myapp.exe`; on macOS it is the `myapp` binary, which also carries its own `.app`. The user downloads that one file and runs it. No installer, no install script, no package manager. This page is the convention for how such an app installs and updates itself, and for who does what.

## Who does what

jr does what has to happen before Java runs, or while the app's own code cannot:

- replacing its own exe with a newer release (`-Xjr:update`), safely even while it runs, after checking the download's SHA-256;
- fetching Java and the app's jar, and checking the jar;
- the app's identity: the exe's icon and version on Windows; on macOS the `.app` with its icon, its Dock name and its bundle id (`-Xjr:install`), and with `jvm=dll` the JVM inside jr's own process, so the Dock tile and "open with" belong to the app rather than to `java`.

Everything else is the app's, in Java: where it installs, whether it goes on PATH, shortcuts, starting at sign-in, file associations, and anything specific to it (stopping a background process before an update, say). [updateutils](https://github.com/jarrunner/updateutils) does the common parts once, so each app does not have to.

## An app's own `install` and `update`

Give the app its own `install` and `update` commands (or menu items). They are the ones users see; jr's `-Xjr:` options are the machinery underneath.

- **`update`**: an app with nothing to do around an update just delegates to jr. One that must stop something first, or tidy up after, does that around it:

```java
var check = Updater.check();
if (check.status() == UpdateResult.Status.NEWER) {
    stopBackgroundProcesses();          // only if the app has any
    var r = Updater.update();           // runs <exe> -Xjr:update -Xjr:batch
    if (r.updated()) warmUpNewVersion(); // optional: fetch the new jar now rather than on first use
}
```

- **`install`**: copies the exe that is running to the user's standard place and wires it in:

```java
Install.app("myapp").displayName("My App").description("Does things").desktopShortcut(true).run();
```

On Windows that is `%LOCALAPPDATA%\Programs\myapp\myapp.exe`, first on the user PATH (Explorer is told, so new terminals see it), Start Menu and Desktop shortcuts. On macOS jr writes `~/Applications/My App.app` and links `~/.local/bin/myapp` to the binary inside it, so one file is both the app and the command. No administrator rights anywhere. A receipt makes running it again a repair and `Install.uninstall("myapp")` exact.

## What a user types

On Windows, in a Command Prompt or PowerShell (`curl.exe` ships with Windows 10 and 11):

```
curl.exe -fLo myapp.exe https://github.com/OWNER/REPO/releases/latest/download/myapp.exe
.\myapp.exe install
```

On macOS, in Terminal:

```
curl -fLo myapp https://github.com/OWNER/REPO/releases/latest/download/myapp && chmod +x myapp && ./myapp install
```

A file fetched by curl carries no download mark, so the unsigned exe meets neither SmartScreen nor Gatekeeper. A browser download works too, after one warning.

## `-Xjr:batch`: running jr's updater from the app

`<exe> -Xjr:update-check -Xjr:batch` and `<exe> -Xjr:update -Xjr:batch` show no dialog and no progress window. Their messages go to stderr, and stdout carries exactly one JSON line:

```json
{"status":"updated","version":"1.0","latest":"1.1","message":"Updated: 1.0 -> 1.1. The new version runs from the next launch."}
```

`status` is `current`, `newer` (check only), `updated` or `error`. The exit codes do not change: update-check 0 current, 10 newer, 1 error; update 0 updated or already current, 1 error. If jr stops before it gets that far (a broken config), there is no line and the exit code is 1. `updateutils` reads this for you.

## Betas: channels

The update file has channels. An exe follows the channel named in its config (`updateChannel` in jr-maven-plugin, default `stable`). Build a beta with `updateChannel` set to `beta`, publish its GitHub release as a pre-release, and the release writer adds `"beta": "<version>"` while keeping `stable` as it was. Installed stable copies are never offered the beta, and `releases/latest` still points at the last stable release.

## The JVM inside jr's process (`jvm=dll`)

On Windows `jvm=dll` has long run the JVM inside the exe. On macOS and Linux it does now too, through the JDK's libjli, off by default while it is new: set `jvmMode` to `dll` in the plugin, or run with `-Xjr:jvm=dll`. It is what a Mac app wants, because the Dock tile, the bundle id and Finder's "open with" then belong to the app's process. When in-process start is not possible (no libjli in that Java, or on Linux an `LD_LIBRARY_PATH` that would make libjli restart the program), jr starts Java as a child as before and says why in its log.

## See also

- [guide.md](guide.md), building an exe with jr-maven-plugin and publishing releases.
- [macos.md](macos.md), jr on macOS.
- [jr-runtime](https://github.com/jarrunner/jr-runtime), for an app that wants to check for updates by itself (in the background, at most once a day) and tell the user.
