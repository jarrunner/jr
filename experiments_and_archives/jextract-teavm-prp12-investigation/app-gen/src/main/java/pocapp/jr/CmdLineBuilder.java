package pocapp.jr;

import java.util.List;

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
        for (var a : extraArgs) {
            sb.append(' ').append(a);
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
            sb.append(' ').append(a);
        }
        return sb.toString();
    }

    private static String aotArg(String jarPath, boolean enableAOT) {
        WinApi.setEnvironmentVariableA(Cstr.of("JR_AOT_STATE"), Cstr.of("off"));
        WinApi.setEnvironmentVariableA(Cstr.of("JR_AOT_CACHE"), Cstr.of(""));
        if (!enableAOT || jarPath.isEmpty()) {
            return "";
        }
        var cachePath = AotCache.buildCacheName(jarPath);
        if (cachePath.isEmpty()) {
            return "";
        }
        AotCache.cleanupOldFiles(jarPath, cachePath);
        var exists = FileIo.exists(cachePath);
        WinApi.setEnvironmentVariableA(Cstr.of("JR_AOT_CACHE"), Cstr.of(cachePath));
        WinApi.setEnvironmentVariableA(Cstr.of("JR_AOT_STATE"), Cstr.of(exists ? "using" : "creating"));
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
