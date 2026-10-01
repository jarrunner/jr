package jarrunner.jr;

import static jarrunner.jr.N.*;

/** The .jrc config file (WinRun4J/jpackage-style key=value), mirroring launcher.c's LauncherConfig.
 *  Always returned non-null from load() (defaults apply whether or not a .jrc exists), matching
 *  launcher.c's initConfig/useConfig split - the "found" field carries what useConfig used to. */
public class Config {
    boolean found = false;
    boolean embedded = false; // found, but read from this exe's own RCDATA/JRC resource, not a file
    String vmArgs = "";
    String javaArgs = "";
    String appArgs = "";
    String logFile = "";
    String logLevel = "info";
    boolean logOverwrite = false;
    int enableAOT = -1;
    int useJvmDll = -1;
    String javaHome = "";
    int javaVersion = 0;          // 0 = not specified: any Java will do, install the default
    boolean javaVersionAtLeast = false; // true if written "NN+" (NN or newer), same convention as jbang's //JAVA
    int javaAutoInstall = -1; // -1 = not specified, use built-in default (enabled)
    String javaType = ""; // "" = not specified, JavaInstall's own default (jre); "jdk" or "jre"
    String runUrl = "";    // run.url / run.maven / run.sha256 - a remote jar, see RemoteJar
    String runMaven = "";
    String runSha256 = "";
    String loadError;       // non-null: the config could not be read (a bad jrc-json); shown, and nothing runs
    boolean json = false;   // jrc-json form (PRP-30); these four exist only there, for the update feature
    String appId = "";
    String appVersion = "";
    String updateUrl = "";
    String updateChannel = "";

    boolean hasRunTarget() {
        return !runUrl.isEmpty() || !runMaven.isEmpty();
    }

    public static Config load(String path) {
        // PRP-30: the embedded config wins and a file beside the exe is then ignored completely, so
        // nobody can change a signed exe's behaviour by planting a file next to it. A key=value .jrc
        // on disk is still read for an exe with nothing embedded, until the launchers in use are
        // re-rolled with the maven plugin; then .jrc support goes. A jrc-json is never read from disk.
        var config = new Config();
        var text = loadEmbedded();
        config.embedded = text != null;
        if (text == null) {
            text = FileIo.readAll(path);
        }
        if (text == null) {
            return config;
        }
        config.found = true;
        Log.info("Loading config: " + (config.embedded ? "embedded RCDATA/JRC resource" : path));
        if (JrcJson.looksLikeJson(text)) {
            config.json = true;
            config.loadError = config.embedded ? JrcJson.load(text, config)
                    : "A jrc-json is read only when it is baked into the exe; bake it in with\n"
                    + "jr.exe -Xjr:edit=<this.exe> -Xjr:resource.RCDATA.JRC=<file>";
            return config;
        }
        for (var rawLine : Lines.split(text)) {
            var line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = line.substring(0, eq).strip();
            var value = line.substring(eq + 1).strip();
            // Unknown keys are ignored here (a .jrc may be shared with a newer jr); on the
            // command line (-Xjr:key=value) they are an error - see JrOptions.parse.
            config.applyKey(key, value);
        }
        return config;
    }

    /** Applies one setting - the .jrc file and -Xjr:key=value command-line overrides both come
     *  through here, so every .jrc key can also be given on the command line. Returns false for an
     *  unknown key, which JrOptions.parse turns into a command-line error (a .jrc simply ignores it). */
    boolean applyKey(String key, String value) {
        switch (AsciiStr.lower(key)) {
            case "vm.args" -> { vmArgs = value; Log.info("vm.args=" + value); }
            case "java.args" -> { javaArgs = value; Log.info("java.args=" + value); }
            case "app.args" -> { appArgs = value; Log.info("app.args=" + value); }
            case "log.file" -> logFile = value;
            case "log.level" -> logLevel = value;
            case "log.overwrite" -> logOverwrite = isTrue(value);
            case "aot" -> {
                if (isTrue(value)) { enableAOT = 1; Log.info("aot=true"); }
                else if (isFalse(value)) { enableAOT = 0; Log.info("aot=false"); }
                else { Log.warn("Unrecognised aot value '" + value + "' (expected true or false)"); }
            }
            case "jvm", "jvm.mode" -> {
                var mode = parseJvmMode(value);
                if (mode != -1) { useJvmDll = mode; Log.info("jvm=" + value); }
                else Log.warn("Unrecognised jvm mode '" + value + "' (expected dll or exe)");
            }
            case "java.home" -> { javaHome = value; Log.info("java.home=" + value); }
            case "java.version" -> {
                javaVersion = Atoi.parse(value);
                javaVersionAtLeast = value.indexOf('+') >= 0;
                Log.info("java.version=" + value);
            }
            case "java.autoinstall" -> { javaAutoInstall = isTrue(value) ? 1 : 0; Log.info("java.autoinstall=" + value); }
            case "run.url" -> { runUrl = value; Log.info("run.url=" + value); }
            case "run.maven" -> { runMaven = value; Log.info("run.maven=" + value); }
            case "run.sha256" -> runSha256 = value;
            case "java.type" -> {
                var lower = AsciiStr.lower(value);
                if (lower.equals("jdk") || lower.equals("jre")) { javaType = lower; Log.info("java.type=" + lower); }
                else Log.warn("Unrecognised java.type '" + value + "' (expected jdk or jre)");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** PRP-24: an exe with no sibling .jrc file can carry its config embedded instead, as an
     *  RT_RCDATA resource named JRC, stamped with the generic raw-resource option:
     *  {@code jr.exe -Xjr:edit=app.exe -Xjr:resource.RCDATA.JRC=app.jrc}. Since PRP-30 the embedded
     *  config wins over any file on disk (see load). Opens its own file as a data file,
     *  the same way -Xjr:list-resources opens a target, and asks which language the resource was
     *  stamped under rather than assuming one. Relies on WinApi being initialized first - see the
     *  top of Jr.main. */
    private static String loadEmbedded() {
        var module = WinApi.loadLibraryExW(wcstr(ExeInfo.fullPath()), NULL,
                WinApi.LOAD_LIBRARY_AS_DATAFILE | WinApi.LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        if (module.toLong() == 0) {
            Dbg.log("loadEmbedded: LoadLibraryExW failed, error " + WinApi.getLastError());
            return null;
        }
        try {
            var type = ResId.of(WinApi.RT_RCDATA);
            var name = ResId.of("JRC");
            var langs = ReCallbacks.getLangs(module, type, name);
            var found = langs.length == 0 ? null : ReEntries.find(module, type, name, langs[0]);
            Dbg.log("loadEmbedded: " + (found == null ? "no RCDATA/JRC resource" : found.size() + " bytes"));
            if (found == null) {
                return null;
            }
            var sb = new StringBuilder(found.size());
            for (var i = 0; i < found.size(); i++) {
                sb.append((char) (found.data().add(i).getByte() & 0xFF));
            }
            return sb.toString();
        } finally {
            WinApi.freeLibrary(module);
        }
    }

    private static boolean isTrue(String v) {
        return AsciiStr.equalsIgnoreCase(v, "true") || v.equals("1");
    }

    private static boolean isFalse(String v) {
        return AsciiStr.equalsIgnoreCase(v, "false") || v.equals("0");
    }

    /** Returns 1 for in-process (jvm.dll), 0 for java.exe, -1 if unrecognised. */
    static int parseJvmMode(String value) {
        return switch (AsciiStr.lower(value)) {
            case "dll", "jvmdll", "jvm.dll", "inprocess", "in-process" -> 1;
            case "exe", "javaexe", "java.exe", "process", "external" -> 0;
            default -> -1;
        };
    }

    public static boolean createSample(String configPath, String jarPath) {
        var sb = new StringBuilder();
        sb.append("# Java Runner Configuration (.jrc format)\n");
        sb.append("# Lines starting with # are comments\n");
        sb.append("# Format follows WinRun4J/jpackage conventions\n");
        sb.append("# Any key can also be overridden for one run on the command line, before\n");
        sb.append("# the app's own arguments: myapp.exe -Xjr:jvm=dll -Xjr:aot=false [app args]\n\n");
        sb.append("# VM arguments (passed before -jar, launcher auto-injects AOT flags here)\n");
        sb.append("#vm.args=-Xmx512m -Xms128m -Dapp.mode=production\n\n");
        sb.append("# Splash screen while the JVM/app starts up (GIF/JPEG/PNG, animated GIF loops).\n");
        sb.append("# This is plain java.awt.SplashScreen (-splash:), just riding vm.args - closes itself\n");
        sb.append("# on the app's first AWT/Swing window, or call SplashScreen.getSplashScreen().close()\n");
        sb.append("# yourself for a JavaFX app (FX has no splash mechanism of its own). Path resolves\n");
        sb.append("# relative to the working directory, same as any other java arg.\n");
        sb.append("#vm.args=-splash:splash.png\n\n");
        sb.append("# Java arguments (everything after VM args: -jar, -cp, class name, etc.)\n");
        if (jarPath != null && !jarPath.isEmpty()) {
            sb.append("java.args=-jar ").append(jarPath).append("\n\n");
        } else {
            sb.append("#java.args=-jar yourapp.jar\n");
            sb.append("# Or for classpath: java.args=-cp lib/*:app.jar com.example.Main\n\n");
        }
        sb.append("# Or instead of java.args, fetch the jar on first run - a GitHub release asset\n");
        sb.append("# or any https link (run.url), or a Maven Central artifact (run.maven) - pinned by\n");
        sb.append("# its SHA-256 (required: the download is not run unless it matches). Downloaded\n");
        sb.append("# once, resumed if interrupted: run.maven into %USERPROFILE%\\.m2\\repository (a jar\n");
        sb.append("# Maven already has is reused), run.url into %USERPROFILE%\\.jr\\cache\\jars\\.\n");
        sb.append("# The jar must be self-contained (shaded): its dependencies are not fetched.\n");
        sb.append("#run.url=https://github.com/owner/repo/releases/download/v1.0/app.jar\n");
        sb.append("#run.maven=com.example:app:1.0\n");
        sb.append("#run.sha256=<64 hex characters>\n\n");
        sb.append("# Application arguments (passed to your main method)\n");
        sb.append("#app.args=--config myconfig.xml --verbose\n\n");
        sb.append("# AOT cache control (optional, default: true)\n");
        sb.append("#aot=true\n\n");
        sb.append("# How the JVM is started (optional, default: exe)\n");
        sb.append("#   exe - spawn java.exe/javaw.exe as a child process\n");
        sb.append("#   dll - load jvm.dll into this process, so the app runs under\n");
        sb.append("#         this executable's own name and can be killed on its own\n");
        sb.append("#jvm=dll\n\n");
        sb.append("# Debug logging (optional, only used when specified)\n");
        sb.append("#log.file=launcher.log\n");
        sb.append("#log.level=info\n");
        sb.append("#log.overwrite=false\n\n");
        sb.append("# Required Java version: 21 = exactly 21, 21+ = 21 or newer (same convention as jbang).\n");
        sb.append("# If the Java in PATH doesn't match (or there is none), jr offers to download a matching\n");
        sb.append("# Eclipse Temurin JDK into %USERPROFILE%\\.jbang\\cache\\jdks\\<version> (same cache jbang itself uses).\n");
        sb.append("#java.version=21+\n");
        sb.append("#java.autoinstall=true\n\n");
        sb.append("# jre (default) or jdk - most apps only ever RUN java and never need javac/jar/etc,\n");
        sb.append("# so auto-install fetches the smaller JRE unless this says otherwise. A build tool,\n");
        sb.append("# an app that shells out to javac, or anything needing the full JDK should set jdk.\n");
        sb.append("#java.type=jre\n\n");
        sb.append("# Use this JDK and nothing else (no PATH lookup, no version check, no install)\n");
        sb.append("#java.home=C:\\Java\\jdk-25\n");
        return FileIo.writeAll(configPath, sb.toString());
    }
}
