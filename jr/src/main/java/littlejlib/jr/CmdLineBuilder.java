package littlejlib.jr;

import java.util.List;

import static littlejlib.jr.N.*;

/** Assembles the final java invocation command line - config mode (a .jrc was found and carries
 *  java.args) or traditional mode (first arg is the jar) - mirroring launcher.c's main() tail. */
public final class CmdLineBuilder {
    private CmdLineBuilder() {}

    public static String buildConfigMode(String javaPath, Config config, List<String> extraArgs,
            boolean enableAOT) {
        var jarPath = JarPath.fromArgsString(config.javaArgs);
        var aotArg = aotArg(jarPath, enableAOT);

        var sb = new StringBuilder();
        sb.append('"').append(javaPath).append('"').append(' ').append(timingProps());
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
        sb.append('"').append(javaPath).append('"').append(' ').append(timingProps());
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
        return "-XX:AOTCacheOutput=\"" + cachePath + "\"";
    }

    private static String timingProps() {
        return "-Djarrunner.start.micros=" + Timing.startMicros()
                + " -Djarrunner.beforejvm.micros=" + Timing.elapsedMicros();
    }
}
