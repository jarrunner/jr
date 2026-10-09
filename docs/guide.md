# Getting started with jr

jr is a small native launcher for Java apps, for Windows and macOS. There are two ways to use it:

1. **Run any jar with jr.** Download `jr.exe` and run `jr.exe myapp.jar`. Good for trying jr, and for running jars you did not build.
2. **Give your own app its own launcher.** Add [jr-maven-plugin](https://github.com/jarrunner/jr-maven-plugin) to your Maven build. Every `mvn package` (on your machine or in CI) then produces `myapp.exe`: your name, icon and version, with your app's settings baked into the exe. This is the way to ship an app.

## 1. Run any jar with jr

Download `jr.exe` from the [releases page](https://github.com/jarrunner/jr/releases) (on macOS, see [macos.md](macos.md)) and run:

```
jr.exe myapp.jar --your --app --args
```

What jr does for you:

- **Finds Java.** It uses an installed Java that fits the app. If there is none, it offers to download one (Eclipse Temurin) into the cache jbang also uses, and asks first unless you pass `-Xjr:yes`.
- **Starts faster from the second run.** On Java 25 or newer it keeps an AOT cache per jar, so later launches start faster. `-Xjr:aot=false` turns it off.
- **Picks the right console.** Double-clicked from Explorer, the app runs with no console window. Run from a terminal, its output shows there.

jr's own options start with `-Xjr:` and come first, before the jar. Everything after them goes to your app untouched:

```
jr.exe -Xjr:java.home=C:\Java\jdk-25 myapp.jar
jr.exe -Xjr:jvm=dll myapp.jar          run the JVM inside jr's own process
jr.exe -Xjr:help
```

On an app's own exe (part 2 below), two more help when something goes wrong: `myapp.exe -Xjr:doctor` shows which Java a launch would use and why, and changes nothing; `myapp.exe -Xjr:repair` fixes what jr made (AOT caches, a changed jar, damaged downloaded JDKs).

## 2. Give your own app its own launcher: jr-maven-plugin

Add the plugin to your app's `pom.xml`, after the shade plugin (or whatever builds your runnable jar), so it hashes the jar that was just written:

```xml
<plugin>
  <groupId>io.github.jarrunner</groupId>
  <artifactId>jr-maven-plugin</artifactId>
  <version>1.1.0</version>
  <executions><execution><goals><goal>exe</goal></goals></execution></executions>
  <configuration>
    <icon>${project.basedir}/app.ico</icon>
    <javaVersion>25+</javaVersion>
    <fileDescription>My App</fileDescription>
  </configuration>
</plugin>
```

Then:

```
mvn package
```

and `target/jr/<artifactId>.exe` runs the jar you just built. The exe carries:

- its settings (Java version, JVM options, arguments), checked by jr itself while the build runs, so a bad setting fails the build rather than the user's launch;
- your icon and version details (what Explorer and Task Manager show);
- an application manifest, so Windows treats it as a modern program.

The settings are baked into the exe and cannot be overridden by a file placed beside it.

### Releases: the exe fetches its jar and updates itself

For a release, point the exe at where the jar is published and build with `source=url`:

```xml
<jarUrl>https://github.com/OWNER/REPO/releases/download/v${project.version}/app.jar</jarUrl>
<updateUrl>https://github.com/OWNER/REPO/releases/latest/download/app.update.json</updateUrl>
```

```
mvn -Djr.source=url package
```

The release exe downloads its jar on first run and checks it against the SHA-256 baked into it. The build also writes `target/jr/release/`, which holds everything a release publishes: the exes, the jar, `SHA256SUMS` and the update file. Your users then run `myapp.exe -Xjr:update-check` and `myapp.exe -Xjr:update` to move to a newer release.

### In CI

The plugin is ordinary Maven, so CI needs nothing beyond `mvn package` on a Windows runner (the Windows exes are stamped with Windows' own resource calls). macOS outputs (`<platforms>`) are built on any OS. A complete example, published as a live site with working updates, is [examples/hello](https://github.com/jarrunner/jr-maven-plugin/tree/main/examples/hello).

Every parameter is listed in the [plugin's README](https://github.com/jarrunner/jr-maven-plugin#parameters). For apps that want to know they were launched by jr, or to check for updates themselves, there is [jr-runtime](https://github.com/jarrunner/jr-runtime).

## Without Maven

jr can stamp an exe directly, which is what the plugin does for you:

```
jr.exe -Xjr:make=myapp.exe -Xjr:icon=app.ico -Xjr:version=1.0.0.0 -Xjr:version.FileDescription="My App" -Xjr:resource.RCDATA.JRC=myapp.jrc.json
```

The config is a jrc-json file. The easiest way to get a correct one is to let the plugin write it once (it keeps a copy as `target/jr/<name>.jrc.json`) and start from that.

## Upgrading from a `.jrc` file

jr 1.2 and earlier also read a `key=value` file named after the exe (`myapp.jrc` beside `myapp.exe`), and `-Xjr:create-config` wrote one. jr 1.3.0 reads neither: a file beside the exe is ignored, and `-Xjr:create-config` is an error. Exes made from an older jr keep the behaviour of the jr they were made from. To move an app on, build its exe with the plugin (part 2 above) and give the `.jrc` keys to the plugin's parameters: `vm.args` becomes `vmArgs`, `java.version` becomes `javaVersion`, `jvm` becomes `jvmMode`, and so on.
