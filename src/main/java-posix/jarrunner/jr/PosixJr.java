package jarrunner.jr;

import static jarrunner.jr.N.*;

/**
 * Java Runner (jr) for Linux/macOS - PRP-21's first real (non-demo) milestone on the POSIX side.
 * Deliberately NOT full parity with the Windows Jr.java: JDK auto-install, the jvm-dll in-process
 * launch mode (no POSIX equivalent - Windows-only trick), and PE resource-editing/signing
 * (-Xjr:make/edit/icon/...) are all out of scope here, per the platform decision recorded in
 * CLAUDE.md and PRP-21's scope note. What this covers: the baked-in config, -Xjr: overrides, AOT cache
 * naming/reuse/cleanup, java.version matching, PATH/--java.home java lookup, and a real
 * posix_spawn launch with the exit code passed straight back - the same feature slice
 * launcher.c itself started from (see prp/01, prp/02) before AOT/config/auto-install were added
 * one at a time. Extend this file the same way, not by trying to match Windows feature-for-
 * feature in one pass.
 */
public final class PosixJr {
    public static void main(String[] args) {
        Timing.init();
        args = utf8Args(args);
        if (Checks.ON && args.length == 1 && args[0].equals("-Xjr:checks-selftest")) { // checks builds only (PRP-35)
            Buf.alloc(4).getInt(2); // 4 bytes at offset 2 of a 4-byte buffer: must throw
        }
        var exeBaseName = ExeInfo.baseNameNoExt();

        var config = Config.load();
        var opts = JrOptions.parse(args, config);

        if (!config.logFile.isBlank()) {
            Log.init(config.logFile, config.logOverwrite);
            Log.info("Launcher started: " + exeBaseName);
            Log.info("Config: " + (config.embedded ? "embedded" : "none baked in"));
        }

        if (opts.error != null) {
            Ui.error(true, "Invalid jr Option", opts.error);
            Log.close();
            PosixApi.exit(1);
            return;
        }

        var javaExeName = "java";
        if (opts.help || (config.javaArgs.isBlank() && !config.hasRunTarget() && opts.appArgs.isEmpty())) {
            var displayJavaPath = JavaFinder.findInPath(javaExeName);
            Help.show(exeBaseName, javaExeName, displayJavaPath, config.embedded);
            Log.close();
            PosixApi.exit(opts.help ? 0 : 1);
            return;
        }

        if (config.loadError != null) {
            Ui.error(true, "Invalid jr Config", "The app's config could not be read:\n" + config.loadError);
            Log.close();
            PosixApi.exit(1);
            return;
        }

        // A remote jar (run.url / run.maven / jar.sources) becomes an ordinary "-jar <cached path>" before
        // anything else looks at java.args, as on Windows, so AOT naming etc. work unchanged.
        if (config.hasRunTarget()) {
            if (!config.javaArgs.isBlank()) {
                Ui.error(true, "Invalid jr Config", "Set java.args or run.url/run.maven, not both.");
                Log.close();
                PosixApi.exit(1);
                return;
            }
            var jar = RemoteJar.resolve(config);
            if (jar == null) {
                Log.close();
                PosixApi.exit(1);
                return;
            }
            config.javaArgs = "-jar " + JrcJson.quote(jar);
        }

        var resolved = resolveJava(config, javaExeName);
        if (resolved == null) {
            Log.close();
            PosixApi.exit(1);
            return;
        }
        var javaPath = resolved[0];
        var javaMajor = Atoi.parse(resolved[1]);

        var enableAOT = config.enableAOT != 0; // default true, matching the Windows side
        java.util.List<String> jvmArgs = config.javaArgs.isBlank()
                ? PosixCmdLineBuilder.buildTraditionalMode(opts.appArgs, enableAOT, javaMajor)
                : PosixCmdLineBuilder.buildConfigMode(config, opts.appArgs, enableAOT, javaMajor);

        var result = ProcessLauncher.launch(javaPath, jvmArgs);
        Log.close();
        PosixApi.exit(result.started ? result.exitCode : 1);
    }

    /** PRP-31: {java path, major}. An explicit java.home is used as given (a warning if its version is outside the
     *  range); otherwise JavaChooser picks by the version rules. Null after printing why nothing qualified. */
    private static String[] resolveJava(Config config, String javaExeName) {
        var range = JavaRange.of(config);
        if (range.error != null) {
            Ui.error(true, "Invalid Java Version Setting", range.error + "\nThis setting is part of the app's own configuration.");
            return null;
        }
        if (!config.javaHome.isBlank()) {
            var javaPath = config.javaHome + "/bin/" + javaExeName;
            if (!FileIo.exists(javaPath)) {
                Ui.error(true, "Java Not Found", "No java at " + javaPath + " (java.home in the app config)");
                return null;
            }
            var major = JavaFinder.releaseMajor(config.javaHome);
            if (!range.accepts(major)) {
                Log.warn("java.home is Java " + major + ", outside " + range.describe() + "; using it because it was named explicitly");
            }
            return new String[] {javaPath, String.valueOf(major)};
        }
        var chooser = new JavaChooser();
        var home = chooser.choose(range);
        if (home == null) {
            Ui.error(true, "Java Not Found", "This app needs Java " + range.preferred + " (" + range.describe() + ").\n"
                    + "Java installations found on this computer:\n" + (chooser.report.length() == 0 ? "- none\n" : chooser.report)
                    + "Install Java " + range.preferred + ", or set java.home in the app config. This build does not download Java.");
            return null;
        }
        Log.info("Using " + home + " (" + chooser.rule + ")");
        return new String[] {home + "/bin/" + javaExeName, String.valueOf(JavaFinder.releaseMajor(home))};
    }

    /** The arguments as the raw UTF-8 bytes the OS holds (PRP-34). TeaVM builds main's args with mbrtoc16 in the
     *  C library's locale, so with LANG unset (locale "C") a non-ASCII argument is cut short. Linux keeps argv in
     *  /proc/self/cmdline, NUL-separated. Anywhere that file is missing (macOS) or disagrees on the count, the
     *  args TeaVM made are kept; the macOS port can read _NSGetArgv() the same way. */
    private static String[] utf8Args(String[] teavmArgs) {
        return memScoped(() -> {
            var f = PosixApi.fopen("/proc/self/cmdline", "rb");
            if (f.toLong() == 0) {
                return teavmArgs;
            }
            var cap = 1 << 20;
            var buf = Buf.alloc(cap + 1);
            var total = 0;
            long got;
            while (total < cap && (got = PosixApi.fread(buf.slice(total, cap - total).ptr(), 1, cap - total, f)) > 0) {
                total += (int) got;
            }
            PosixApi.fclose(f);
            var out = new java.util.ArrayList<String>();
            var start = 0;
            for (var i = 0; i < total; i++) {
                if (buf.getByte(i) == 0) {
                    out.add(text(buf.slice(start, i - start).ptr(), i - start));
                    start = i + 1;
                }
            }
            if (total == cap || out.size() != teavmArgs.length + 1) {
                return teavmArgs;
            }
            var args = new String[teavmArgs.length];
            for (var i = 0; i < args.length; i++) args[i] = out.get(i + 1);
            return args;
        });
    }
}
