package jarrunner.jr;

/**
 * Java Runner (jr) for Linux/macOS - PRP-21's first real (non-demo) milestone on the POSIX side.
 * Deliberately NOT full parity with the Windows Jr.java: JDK auto-install, the jvm-dll in-process
 * launch mode (no POSIX equivalent - Windows-only trick), and PE resource-editing/signing
 * (-Xjr:make/edit/icon/...) are all out of scope here, per the platform decision recorded in
 * CLAUDE.md and PRP-21's scope note. What this covers: .jrc config, -Xjr: overrides, AOT cache
 * naming/reuse/cleanup, java.version matching, PATH/--java.home java lookup, and a real
 * posix_spawn launch with the exit code passed straight back - the same feature slice
 * launcher.c itself started from (see prp/01, prp/02) before AOT/config/auto-install were added
 * one at a time. Extend this file the same way, not by trying to match Windows feature-for-
 * feature in one pass.
 */
public final class PosixJr {
    public static void main(String[] args) {
        Timing.init();
        var exeBaseName = ExeInfo.baseNameNoExt();
        var configPath = ExeInfo.fullPathNoExt() + ".jrc";

        var config = Config.load(configPath);
        var opts = JrOptions.parse(args, config);

        if (!config.logFile.isBlank()) {
            Log.init(config.logFile, config.logOverwrite);
            Log.info("Launcher started: " + exeBaseName);
            Log.info("Config file: " + configPath + " (" + (config.found ? "found" : "not found") + ")");
        }

        if (opts.error != null) {
            Ui.error(true, "Invalid jr Option", opts.error);
            Log.close();
            PosixApi.exit(1);
            return;
        }

        if (opts.createConfig) {
            var ok = Config.createSample(configPath, opts.createConfigJar);
            if (ok) {
                System.out.println("Wrote " + configPath);
            } else {
                System.err.println("Could not write " + configPath);
            }
            Log.close();
            PosixApi.exit(ok ? 0 : 1);
            return;
        }

        var javaExeName = "java";
        if (opts.help || (config.javaArgs.isBlank() && opts.appArgs.isEmpty())) {
            var displayJavaPath = JavaFinder.findInPath(javaExeName);
            Help.show(exeBaseName, javaExeName, displayJavaPath, configPath, config.found);
            Log.close();
            PosixApi.exit(opts.help ? 0 : 1);
            return;
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
}
