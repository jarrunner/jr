# jr-runtime

Optional library for a Java app launched by a [jr](../jr) exe. `io.github.jarrunner:jr-runtime`, Java 21, no dependencies.

jr passes the app everything about itself as system properties: every leaf of the exe's embedded jrc-json as `io.github.jarrunner.jr.<path>` (`app.id`, `app.version`, `update.url`, `jvm.vmArgs.0`, ...), plus `exe` (the launcher's path), `startMicros` and `beforeJvmMicros`. Those properties are the API; this library only gives them names and types, and adds an update check. An app can use the properties directly and never depend on this.

## JrApp

`JrApp.launchedByJr()`, `exe()`, `exeName()`, `appId()`, `appVersion()`, `updateUrl()`, `updateChannel()` (default `stable`), `config("jvm.mode")` for any leaf, `configList("app.args")` for a list. All empty when the app runs without jr (plain java, an IDE, tests), so calling them unconditionally is safe.

## UpdateCheck

Reads the same update file as `<exe> -Xjr:update-check` and decides by the same rules: releases newest first, this build finds its own `app.version` by exact match, the channel names the release it should be on, version strings are never compared. Status is `CURRENT`, `NEWER`, `NOT_CONFIGURED` (no jr, or no `update.url` / `app.version`) or `ERROR`.

- `check()` checks now.
- `checkAtMostEvery(Duration)` checks at most once per interval, caching the result in `~/.jr/update-check/<app.id>.json`. The cache only counts for the same url, version and channel, so after an update the next start checks again. Errors are not cached.
- `inBackground(Duration, Consumer<Result>)` runs that on a daemon thread, so startup never waits on the network.
- `notice(Result)` is one line for the user, or null: `demo 1.1 is available (this is 1.0): run demo -Xjr:update`.

Nothing runs or prints unless the app asks, and installing stays jr's job (`-Xjr:update` replaces the exe). When and whether to tell the user is the app's decision. For example, a CLI whose stdout is data:

```java
UpdateCheck.inBackground(Duration.ofDays(1), r -> {
    var line = UpdateCheck.notice(r);
    if (line != null && !jsonOutput) System.err.println(line);
});
```

A window app would show the same `Result` as a tray message or a line in its about box instead.
