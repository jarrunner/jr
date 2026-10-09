package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

/** Assembles the java argv (excluding the java binary itself, which ProcessLauncher.launch takes
 *  separately) - config mode (the config carries java.args) or traditional mode (first
 *  arg is the jar), mirroring the Windows CmdLineBuilder. Builds a real token LIST rather than a
 *  quoted string - there is no WinQuote-style requoting step here, and none is needed: each
 *  argument keeps its own identity all the way to posix_spawn's argv[]. */
public final class PosixCmdLineBuilder {
    private PosixCmdLineBuilder() {}

    public static List<String> buildConfigMode(Config config, List<String> extraArgs, boolean enableAOT,
            int javaMajor) {
        var jarPath = JarPath.fromArgsString(config.javaArgs);
        var out = new ArrayList<String>();
        addJrProps(out, config);
        if (!config.vmArgs.contains("-Xdock:")) {
            Os.bundleVmArgs(out); // before vm.args, which may override it
        }
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
        addJrProps(out, null);
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
            out.add("-XX:+DisplayVMOutputToStderr");   // see CmdLineBuilder.aotArg
        }
    }

    /** Same -Dio.github.jarrunner.jr.* properties as the Windows CmdLineBuilder (PRP-30): timings, the exe
     *  path, and the whole jrc-json, one property per leaf named by its path (app.id, update.url, ...). */
    private static void addJrProps(List<String> out, Config config) {
        out.add("-Dio.github.jarrunner.jr.startMicros=" + Timing.startMicros());
        out.add("-Dio.github.jarrunner.jr.beforeJvmMicros=" + Timing.elapsedMicros());
        out.add("-Dio.github.jarrunner.jr.exe=" + ExeInfo.fullPath());
        if (config != null && config.jsonRoot != null) {
            flatten(out, "", config.jsonRoot);
        }
    }

    private static void flatten(List<String> out, String path, JsonValue v) {
        var k = v.kind();
        if (k == JsonValue.OBJECT || k == JsonValue.ARRAY) {
            for (var i = 0; i < v.size(); i++) {
                var name = k == JsonValue.OBJECT ? v.keyAt(i) : Integer.toString(i);
                flatten(out, path.isEmpty() ? name : path + "." + name, v.at(i));
            }
        } else if (k == JsonValue.STRING || k == JsonValue.NUMBER) {
            var value = k == JsonValue.STRING ? v.str() : v.num();
            if (value != null && !value.isEmpty()) out.add("-Dio.github.jarrunner.jr." + path + "=" + value);
        } else if (k == JsonValue.TRUE || k == JsonValue.FALSE) {
            out.add("-Dio.github.jarrunner.jr." + path + "=" + (k == JsonValue.TRUE ? "true" : "false"));
        }
    }

    /** Splits a raw config string (vm.args/java.args/app.args) into arguments on whitespace, where double
     *  quotes group (and are removed): java.args=-jar "/home/me/My Apps/app.jar" is two arguments, as on
     *  Windows. Before PRP-34 the quotes were passed on literally and the path was cut at its first space. */
    private static void addWhitespaceSplit(List<String> out, String s) {
        if (s == null || s.isBlank()) {
            return;
        }
        var cur = new StringBuilder();
        var inToken = false;
        var quoted = false;
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                inToken = true;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(cur.toString());
        }
    }
}
