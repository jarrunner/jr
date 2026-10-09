package jarrunner.jr;

import static jarrunner.jr.N.*;

/** jr's settings: the jrc-json baked into this exe (PRP-30), with -Xjr:key=value overrides on top.
 *  Always returned non-null from load(); with nothing baked in, the defaults apply. */
public class Config {
    boolean embedded = false; // a config is baked into this exe (its RCDATA/JRC resource)
    String vmArgs = "";
    String javaArgs = "";
    String appArgs = "";
    String logFile = "";
    String logLevel = "info";
    boolean logOverwrite = false;
    int enableAOT = -1;
    int useJvmDll = -1;
    String javaHome = "";
    int javaMin = 0, javaPreferred = 0, javaMax = 0; // 0 = not declared; see JavaRange for the defaults
    boolean javaVersionSet = false, javaKeysSet = false; // java.version and java.min/preferred/max are exclusive
    String javaRangeError;        // a java.* value that is not a major number
    int javaAutoInstall = -1; // -1 = not specified, use built-in default (enabled)
    String javaType = ""; // "" = not specified, JavaInstall's own default (jre); "jdk" or "jre"
    String runUrl = "";    // run.url / run.maven / run.sha256 - a remote jar, see RemoteJar
    String runMaven = "";
    String runSha256 = "";
    String runVerify = "";  // per-run jar check: "" = crc32 (default), "sha256", "none" - see JarCheck
    String runCrc32 = "";   // the jar's CRC32 as baked in by the maven plugin
    String loadError;       // non-null: the config could not be read (a bad jrc-json); shown, and nothing runs
    boolean json = false;   // jrc-json form (PRP-30); these four exist only there, for the update feature
    String appId = "";
    String appVersion = "";
    String updateUrl = "";
    String updateChannel = "";
    String supportName = "", supportEmail = "", supportIssues = "", supportUrl = ""; // PRP-31: shown in the error dialog
    // jrc-json jar.sources to download, in order: "m<group:artifact:version>\n" or "u<https url>\n" entries
    String sources = "";
    JsonValue jsonRoot;     // the parsed jrc-json, passed to the app whole as -D properties (CmdLineBuilder)

    /** Where to send someone who needs a config: jr-maven-plugin, which bakes one in. */
    static final String GUIDE = "https://github.com/jarrunner/jr/blob/main/docs/guide.md";

    boolean hasRunTarget() {
        return !runUrl.isEmpty() || !runMaven.isEmpty() || !sources.isEmpty();
    }

    /** The config baked into this exe, or the defaults when there is none. jr reads nothing from disk
     *  (PRP-38): nobody can change an exe's behaviour by planting a file next to it. */
    public static Config load() {
        var config = new Config();
        var text = loadEmbedded();
        if (text == null) {
            return config;
        }
        config.embedded = true;
        config.json = true;
        Log.info("Loading config: embedded RCDATA/JRC resource");
        config.loadError = JrcJson.looksLikeJson(text) ? JrcJson.load(text, config)
                : "The config baked into this exe is not a jrc-json. Rebuild the exe with jr-maven-plugin:\n" + GUIDE;
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
            case "support.name" -> supportName = value;
            case "support.email" -> supportEmail = value;
            case "support.issues" -> supportIssues = value;
            case "support.url" -> supportUrl = value;
            case "java.autoinstall" -> { javaAutoInstall = isTrue(value) ? 1 : 0; Log.info("java.autoinstall=" + value); }
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
                if (lower.equals("jdk") || lower.equals("jre")) { javaType = lower; Log.info("java.type=" + lower); }
                else Log.warn("Unrecognised java.type '" + value + "' (expected jdk or jre)");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** PRP-24: the config is an RT_RCDATA resource named JRC, stamped with the generic raw-resource
     *  option: {@code jr.exe -Xjr:edit=app.exe -Xjr:resource.RCDATA.JRC=app.jrc.json} (jr-maven-plugin
     *  does this). Opens its own file as a data file,
     *  the same way -Xjr:list-resources opens a target, and asks which language the resource was
     *  stamped under rather than assuming one. Relies on WinApi being initialized first - see the
     *  top of Jr.main. */
    private static String loadEmbedded() {
        var module = WinApi.loadLibraryExW(ExeInfo.fullPath(), NULL, WinApi.LOAD_LIBRARY_AS_DATAFILE | WinApi.LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        if (module.toLong() == 0) {
            Dbg.log("loadEmbedded: LoadLibraryExW failed, error " + WinApi.getLastError());
            return null;
        }
        try {
            var type = ResId.of(WinApi.RT_RCDATA);
            var name = ResId.of("JRC");
            var langs = ReCallbacks.getLangs(module, type, name);
            var found = langs.length == 0 ? null : ReEntries.find(module, type, name, langs[0]);
            Dbg.log("loadEmbedded: " + (found == null ? "no RCDATA/JRC resource" : found.length + " bytes"));
            if (found == null) {
                return null;
            }
            return text(found);
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
}
