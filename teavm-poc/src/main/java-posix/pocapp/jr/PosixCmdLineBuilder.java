package pocapp.jr;

import java.util.ArrayList;
import java.util.List;

/** Assembles the java argv (excluding the java binary itself, which ProcessLauncher.launch takes
 *  separately) - config mode (a .jrc was found and carries java.args) or traditional mode (first
 *  arg is the jar), mirroring the Windows CmdLineBuilder. Builds a real token LIST rather than a
 *  quoted string - there is no WinQuote-style requoting step here, and none is needed: each
 *  argument keeps its own identity all the way to posix_spawn's argv[]. */
public final class PosixCmdLineBuilder {
    private PosixCmdLineBuilder() {}

    public static List<String> buildConfigMode(Config config, List<String> extraArgs, boolean enableAOT,
            int javaMajor) {
        var jarPath = JarPath.fromArgsString(config.javaArgs);
        var out = new ArrayList<String>();
        addTimingProps(out);
        addWhitespaceSplit(out, config.vmArgs);
        addAotArg(out, jarPath, enableAOT, javaMajor);
        addWhitespaceSplit(out, config.javaArgs);
        addWhitespaceSplit(out, config.appArgs);
        out.addAll(extraArgs);
        return out;
    }

    public static List<String> buildTraditionalMode(List<String> tokens, boolean enableAOT, int javaMajor) {
        var jarPath = JarPath.fromTokens(tokens);
        var out = new ArrayList<String>();
        addTimingProps(out);
        addAotArg(out, jarPath, enableAOT, javaMajor);
        out.add("-jar");
        out.addAll(tokens);
        return out;
    }

    /** -XX:AOTCache(Output) is a JDK 25+ flag (Project Leyden) - on an older JDK it is not just
     *  ignored, it is a fatal "Unrecognized VM option" that stops the JVM from starting at all
     *  (found the hard way testing this build against a real JDK 21 - see PRP-21's status file).
     *  Mirrors the same gate launcher.c/javainstall.c already carry (PRP-13) and the TeaVM Windows
     *  port added in PRP-20 phase 1. */
    private static void addAotArg(List<String> out, String jarPath, boolean enableAOT, int javaMajor) {
        if (!enableAOT || jarPath.isEmpty() || javaMajor < 25) {
            return;
        }
        var cachePath = AotCache.buildCacheName(jarPath);
        if (cachePath.isEmpty()) {
            return;
        }
        AotCache.cleanupOldFiles(jarPath, cachePath);
        var exists = FileIo.exists(cachePath);
        if (exists) {
            Log.info("Using existing AOT cache: " + cachePath);
            out.add("-XX:AOTCache=" + cachePath);
        } else {
            Log.info("Creating new AOT cache: " + cachePath);
            out.add("-XX:AOTCacheOutput=" + cachePath);
        }
    }

    private static void addTimingProps(List<String> out) {
        out.add("-Djarrunner.start.micros=" + Timing.startMicros());
        out.add("-Djarrunner.beforejvm.micros=" + Timing.elapsedMicros());
    }

    /** Same hand-rolled tokenizer as JarPath - a raw .jrc string (vm.args/java.args/app.args) has
     *  no quoting of its own to preserve, unlike extraArgs which arrives pre-tokenized already. */
    private static void addWhitespaceSplit(List<String> out, String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        var start = -1;
        for (var i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            out.add(s.substring(start));
        }
    }
}
