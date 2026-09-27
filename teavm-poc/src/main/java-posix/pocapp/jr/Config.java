package pocapp.jr;

/** The .jrc config file (WinRun4J/jpackage-style key=value), mirroring launcher.c's LauncherConfig.
 *  Always returned non-null from load() (defaults apply whether or not a .jrc exists), matching
 *  launcher.c's initConfig/useConfig split - the "found" field carries what useConfig used to. */
public class Config {
    boolean found = false;
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

    public static Config load(String path) {
        var config = new Config();
        var text = FileIo.readAll(path);
        if (text == null) {
            return config;
        }
        config.found = true;
        Log.info("Loading config file: " + path);
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
            default -> {
                return false;
            }
        }
        return true;
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
        sb.append("# Java arguments (everything after VM args: -jar, -cp, class name, etc.)\n");
        if (jarPath != null && !jarPath.isEmpty()) {
            sb.append("java.args=-jar ").append(jarPath).append("\n\n");
        } else {
            sb.append("#java.args=-jar yourapp.jar\n");
            sb.append("# Or for classpath: java.args=-cp lib/*:app.jar com.example.Main\n\n");
        }
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
        sb.append("# Use this JDK and nothing else (no PATH lookup, no version check, no install)\n");
        sb.append("#java.home=C:\\Java\\jdk-25\n");
        return FileIo.writeAll(configPath, sb.toString());
    }
}
