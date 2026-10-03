package jarrunner.jr;

import java.util.List;

import static jarrunner.jr.N.*;

/** Assembles the final java invocation command line - config mode (a .jrc was found and carries
 *  java.args) or traditional mode (first arg is the jar) - mirroring launcher.c's main() tail. */
public final class CmdLineBuilder {
    private CmdLineBuilder() {}

    public static String buildConfigMode(String javaPath, Config config, List<String> extraArgs,
            boolean enableAOT) {
        var jarPath = JarPath.fromArgsString(config.javaArgs);
        var aotArg = aotArg(jarPath, enableAOT);

        var sb = new StringBuilder();
        sb.append('"').append(javaPath).append('"').append(' ').append(jrProps(config));
        if (!config.vmArgs.isBlank()) {
            sb.append(' ').append(config.vmArgs);
        }
        if (!aotArg.isEmpty()) {
            sb.append(' ').append(aotArg);
        }
        sb.append(' ').append(config.javaArgs);
        if (!config.appArgs.isBlank()) {
            sb.append(' ').append(config.appArgs);
        }
        // extraArgs came through TeaVM's already-split args[], which has thrown away whatever
        // quoting the user originally typed - requote each token so an embedded space survives as
        // ONE argument on the far side too (the "two words" bug - see 20-prp-teavm_port_catches_up_
        // with_the_c_launcher.md item 5). config.javaArgs/appArgs above are raw .jrc strings, never
        // split, so they are spliced in verbatim, exactly as launcher.c does.
        for (var a : extraArgs) {
            sb.append(' ').append(WinQuote.quote(a));
        }
        return sb.toString();
    }

    public static String buildTraditionalMode(String javaPath, List<String> tokens, boolean enableAOT) {
        var jarPath = JarPath.fromTokens(tokens);
        var aotArg = aotArg(jarPath, enableAOT);

        var sb = new StringBuilder();
        sb.append('"').append(javaPath).append('"').append(' ').append(jrProps(null));
        if (!aotArg.isEmpty()) {
            sb.append(' ').append(aotArg);
        }
        sb.append(" -jar");
        for (var a : tokens) {
            sb.append(' ').append(WinQuote.quote(a));
        }
        return sb.toString();
    }

    private static String aotArg(String jarPath, boolean enableAOT) {
        WinApi.setEnvironmentVariableA(cstr("JR_AOT_STATE"), cstr("off"));
        WinApi.setEnvironmentVariableA(cstr("JR_AOT_CACHE"), cstr(""));
        if (!enableAOT || jarPath.isEmpty()) {
            return "";
        }
        var cachePath = AotCache.buildCacheName(jarPath);
        AotCache.lastPath = cachePath;
        if (cachePath.isEmpty()) {
            return "";
        }
        AotCache.cleanupOldFiles(jarPath, cachePath);
        var exists = FileIo.exists(cachePath);
        WinApi.setEnvironmentVariableA(cstr("JR_AOT_CACHE"), cstr(cachePath));
        WinApi.setEnvironmentVariableA(cstr("JR_AOT_STATE"), cstr(exists ? "using" : "creating"));
        if (exists) {
            Log.info("Using existing AOT cache: " + cachePath);
            return "-XX:AOTCache=\"" + cachePath + "\"";
        }
        Log.info("Creating new AOT cache: " + cachePath);
        // JDK 25 reports the one-step cache creation with five unconditional lines (no -Xlog
        // setting silences them) on stdout, where they would land in a CLI's output. Moved to
        // stderr, on this one run only (PRP-30).
        return "-XX:AOTCacheOutput=\"" + cachePath + "\" -XX:+DisplayVMOutputToStderr";
    }

    /** What jr tells the app about itself, as -Dio.github.jarrunner.jr.* properties (PRP-30): start
     *  timings, its own exe path, and the whole jrc-json, one property per leaf named by its path
     *  (app.id, update.url, jvm.vmArgs.0, ...). That convention is the API an app-side update notice
     *  reads. Config may be null (no config: traditional mode). */
    private static String jrProps(Config c) {
        var sb = new StringBuilder();
        prop(sb, "startMicros", Long.toString(Timing.startMicros()));
        prop(sb, "beforeJvmMicros", Long.toString(Timing.elapsedMicros()));
        prop(sb, "exe", ExeInfo.fullPath());
        if (c != null && c.jsonRoot != null) {
            flatten(sb, "", c.jsonRoot);
        }
        // PRP-31: a native JVM crash log (hs_err) goes where jr's report can find it, not into whatever the
        // current directory happened to be. An ErrorFile in the app's own vm.args wins.
        var crashDir = c != null && c.vmArgs.contains("ErrorFile") ? null : JrDirs.of("crash");
        if (crashDir != null) {
            sb.append(" \"-XX:ErrorFile=").append(crashDir).append('\\').append(ExeInfo.baseNameNoExt()).append("-hs_err_pid%p.log\"");
        }
        return sb.toString();
    }

    private static void flatten(StringBuilder sb, String path, JsonValue v) {
        var k = v.kind();
        if (k == JsonValue.OBJECT || k == JsonValue.ARRAY) {
            for (var i = 0; i < v.size(); i++) {
                var name = k == JsonValue.OBJECT ? v.keyAt(i) : Integer.toString(i);
                flatten(sb, path.isEmpty() ? name : path + "." + name, v.at(i));
            }
        } else if (k == JsonValue.STRING || k == JsonValue.NUMBER) {
            prop(sb, path, k == JsonValue.STRING ? v.str() : v.num());
        } else if (k == JsonValue.TRUE || k == JsonValue.FALSE) {
            prop(sb, path, k == JsonValue.TRUE ? "true" : "false");
        }
    }

    private static void prop(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(WinQuote.quote(PROP_PREFIX + key + "=" + value));
    }

    private static final String PROP_PREFIX = "-Dio.github.jarrunner.jr.";
}
