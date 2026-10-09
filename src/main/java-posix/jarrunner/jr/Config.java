package jarrunner.jr;

/** jr's settings: the jrc-json embedded in this binary (PRP-36), with -Xjr:key=value overrides on top.
 *  Always returned non-null from load(); with nothing embedded, the defaults apply. */
public class Config {
    String vmArgs = "";
    String javaArgs = "";
    String appArgs = "";
    String logFile = "";
    String logLevel = "info";
    boolean logOverwrite = false;
    int enableAOT = -1;
    int useJvmDll = -1;
    String javaHome = "";
    int javaMin = 0, javaPreferred = 0, javaMax = 0; // 0 = not declared; see JavaRange (PRP-31, same rules as Windows)
    boolean javaVersionSet = false, javaKeysSet = false;
    String javaRangeError;
    int javaAutoInstall = -1; // -1 = not specified, use built-in default (enabled)
    // PRP-36: the jrc-json the maven plugin embeds, the same keys as the Windows Config
    boolean embedded = false; // a config is embedded in this binary (its __DATA,__jrc section)
    String loadError;         // a config that could not be read; reported before anything runs
    String javaType = "";     // read for parity; this build does not download Java
    String runUrl = "";       // run.url / run.maven / run.sha256 - a remote jar, see RemoteJar
    String runMaven = "";
    String runSha256 = "";
    String runVerify = "";    // per-run jar check: "" = crc32 (default), "sha256", "none" - see JarCheck
    String runCrc32 = "";
    String appId = "";
    String appVersion = "";
    String updateUrl = "";
    String updateChannel = "";
    String supportName = "", supportEmail = "", supportIssues = "", supportUrl = "";
    // jrc-json jar.sources to download, in order: "m<group:artifact:version>\n" or "u<https url>\n" entries
    String sources = "";
    JsonValue jsonRoot;       // the parsed jrc-json, passed to the app as -D properties (PosixCmdLineBuilder)

    boolean hasRunTarget() {
        return !runUrl.isEmpty() || !runMaven.isEmpty() || !sources.isEmpty();
    }

    /** Where to send someone who needs a config: jr-maven-plugin, which bakes one in. */
    static final String GUIDE = "https://github.com/jarrunner/jr/blob/main/docs/guide.md";

    /** The config embedded in this binary, or the defaults when there is none. As on Windows, jr reads
     *  nothing from disk (PRP-38): nobody can change an app's behaviour by planting a file next to it. */
    public static Config load() {
        var config = new Config();
        var text = Os.embeddedConfig();
        if (text == null) {
            return config;
        }
        config.embedded = true;
        Log.info("Loading config: embedded __DATA,__jrc section");
        config.loadError = JrcJson.looksLikeJson(text) ? JrcJson.load(text, config)
                : "The config embedded in this binary is not a jrc-json. Rebuild it with jr-maven-plugin:\n" + GUIDE;
        return config;
    }

    /** Applies one setting from the jrc-json or a -Xjr:key=value command-line override. Returns false
     *  for an unknown key, which JrOptions.parse turns into a command-line error. */
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
            case "java.version" -> JavaRange.declareVersion(this, value);
            case "java.min" -> { javaKeysSet = true; javaMin = JavaRange.declared(this, key, value); }
            case "java.preferred" -> { javaKeysSet = true; javaPreferred = JavaRange.declared(this, key, value); }
            case "java.max" -> { javaKeysSet = true; javaMax = JavaRange.declared(this, key, value); }
            case "java.autoinstall" -> { javaAutoInstall = isTrue(value) ? 1 : 0; Log.info("java.autoinstall=" + value); }
            case "support.name" -> supportName = value;
            case "support.email" -> supportEmail = value;
            case "support.issues" -> supportIssues = value;
            case "support.url" -> supportUrl = value;
            case "run.url" -> { runUrl = value; Log.info("run.url=" + value); }
            case "run.maven" -> { runMaven = value; Log.info("run.maven=" + value); }
            case "run.sha256" -> runSha256 = value;
            case "run.crc32" -> runCrc32 = AsciiStr.lower(value);
            case "run.verify" -> {
                var lower = AsciiStr.lower(value);
                if (lower.equals("crc32") || lower.equals("sha256") || lower.equals("none")) runVerify = lower;
                else Log.warn("Unrecognised run.verify '" + value + "' (expected crc32, sha256 or none); using crc32");
            }
            case "java.type" -> {
                var lower = AsciiStr.lower(value);
                if (lower.equals("jdk") || lower.equals("jre")) javaType = lower;
                else Log.warn("Unrecognised java.type '" + value + "' (expected jdk or jre)");
            }
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
}
