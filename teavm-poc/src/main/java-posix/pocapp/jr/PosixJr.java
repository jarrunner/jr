package pocapp.jr;

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

        var javaPath = resolveJavaPath(config, javaExeName);
        if (javaPath == null) {
            Log.close();
            PosixApi.exit(1);
            return;
        }

        var enableAOT = config.enableAOT != 0; // default true, matching the Windows side
        var javaMajor = JavaFinder.detectMajorVersion(javaPath);
        java.util.List<String> jvmArgs = config.javaArgs.isBlank()
                ? PosixCmdLineBuilder.buildTraditionalMode(opts.appArgs, enableAOT, javaMajor)
                : PosixCmdLineBuilder.buildConfigMode(config, opts.appArgs, enableAOT, javaMajor);

        var result = ProcessLauncher.launch(javaPath, jvmArgs);
        Log.close();
        PosixApi.exit(result.started ? result.exitCode : 1);
    }

    /** PATH lookup with an optional java.version NN/NN+ check - no auto-install: if the version
     *  doesn't match, this reports the mismatch and stops, rather than silently running the wrong
     *  Java or (like the Windows side) offering to download one. Returns null and has already
     *  printed the error if no usable java was found. */
    private static String resolveJavaPath(Config config, String javaExeName) {
        String javaPath;
        if (!config.javaHome.isBlank()) {
            javaPath = config.javaHome + "/bin/" + javaExeName;
            if (!FileIo.exists(javaPath)) {
                Ui.error(true, "Java Not Found", "No java at " + javaPath + " (from java.home in " + "the .jrc)");
                return null;
            }
        } else {
            javaPath = JavaFinder.findInPath(javaExeName);
            if (javaPath == null) {
                Ui.error(true, "Java Not Found", "No java on PATH, and no java.home set in the .jrc.\n"
                        + "This build does not auto-install a JDK (see PRP-21) - install one and "
                        + "make sure it is on PATH, or set java.home in the .jrc.");
                return null;
            }
        }

        if (config.javaVersion > 0) {
            var major = JavaFinder.detectMajorVersion(javaPath);
            var matches = config.javaVersionAtLeast ? major >= config.javaVersion : major == config.javaVersion;
            if (!matches) {
                Ui.error(true, "Wrong Java Version", javaPath + " is Java " + major + ", but this app needs "
                        + config.javaVersion + (config.javaVersionAtLeast ? "+" : "") + ".\n"
                        + "This build does not auto-install a JDK (see PRP-21) - point java.home at one, "
                        + "or put a matching java on PATH.");
                return null;
            }
        }
        return javaPath;
    }
}
