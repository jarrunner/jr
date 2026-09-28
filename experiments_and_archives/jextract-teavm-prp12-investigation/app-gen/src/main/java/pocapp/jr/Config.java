package pocapp.jr;

/** The .jrc config file (WinRun4J/jpackage-style key=value), mirroring launcher.c's LauncherConfig. */
public class Config {
    String vmArgs = "";
    String javaArgs = "";
    String appArgs = "";
    String logFile = "";
    String logLevel = "info";
    boolean logOverwrite = false;
    int enableAOT = -1;
    int useJvmDll = -1;
    int javaVersion = 0;      // 0 = not specified, use built-in default
    int javaAutoInstall = -1; // -1 = not specified, use built-in default (enabled)

    public static Config load(String path) {
        var text = FileIo.readAll(path);
        if (text == null) {
            return null;
        }
        var config = new Config();
        Log.info("Loading config file: " + path);
        for (var rawLine : splitLines(text)) {
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
            config.apply(key, value);
        }
        return config;
    }

    /** Hand-rolled line splitter - avoids String.split() pulling java.util.regex.Pattern into the
     *  TeaVM build (a real size contributor - see 11-prp.01.size-experiments.md) just for a literal
     *  "\n" separator; TeaVM's dependency analysis can't tell the fast-path-only call from a real
     *  regex use, since both go through the same String.split() bytecode. */
    private static java.util.List<String> splitLines(String text) {
        var out = new java.util.ArrayList<String>();
        var start = 0;
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private void apply(String key, String value) {
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
            }
            case "jvm", "jvm.mode" -> {
                var mode = parseJvmMode(value);
                if (mode != -1) { useJvmDll = mode; Log.info("jvm=" + value); }
                else Log.warn("Unrecognised jvm mode '" + value + "' (expected dll or exe)");
            }
            case "java.version" -> { javaVersion = parseIntOrZero(value); Log.info("java.version=" + value); }
            case "java.autoinstall" -> { javaAutoInstall = isTrue(value) ? 1 : 0; Log.info("java.autoinstall=" + value); }
            default -> { }
        }
    }

    private static boolean isTrue(String v) {
        return AsciiStr.equalsIgnoreCase(v, "true") || v.equals("1");
    }

    private static boolean isFalse(String v) {
        return AsciiStr.equalsIgnoreCase(v, "false") || v.equals("0");
    }

    /** Matches C's atoi(): 0 for anything that isn't a valid leading integer, never throws. */
    private static int parseIntOrZero(String v) {
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        sb.append("# Format follows WinRun4J/jpackage conventions\n\n");
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
        sb.append("# If Java is not found, jr offers to download a matching Eclipse Temurin JDK\n");
        sb.append("# into %USERPROFILE%\\.jbang\\cache\\jdks\\<version> (same cache jbang itself uses).\n");
        sb.append("#java.version=21\n");
        sb.append("#java.autoinstall=true\n");
        return FileIo.writeAll(configPath, sb.toString());
    }
}
