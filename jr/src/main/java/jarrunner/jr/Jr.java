package jarrunner.jr;

import static jarrunner.jr.N.*;


/**
 * Java Runner (jr) - full port to TeaVM's C backend, mirroring launcher.c feature-for-feature.
 * See ../../../../prp/07-prp.02.step1-findings.md for the size/behaviour comparison against the
 * hand-written C original, and guidelines.teavmcpp.md for every toolchain gotcha hit building this.
 * prp/20-prp-teavm_port_catches_up_with_the_c_launcher.md tracks catching this port up to features
 * that were, at the time, exclusive to launcher.c (-Xjr: options, java.version matching, the AOT/
 * JDK-25 gate, argument requoting).
 */
public final class Jr {
    public static void main(String[] args) {
        // Forces WinApi's static initializer before anything else runs. Its Address constants
        // (INVALID_HANDLE_VALUE, RT_*) are set there, and a read that follows a WinApi @Import
        // call in the same method can skip the class-init check (the native call is assumed to
        // have initialized the class, but natives never do) - measured 2026-09-29: RT_RCDATA read
        // 0 inside Config.loadEmbedded, so its resource lookup silently asked for type 0.
        if (WinApi.INVALID_HANDLE_VALUE.toLong() != -1) {
            Dbg.log("WinApi constants not initialized at startup");
        }
        Timing.init();
        var exeBaseName = ExeInfo.baseNameNoExt();
        var configPath = ExeInfo.fullPathNoExt() + ".jrc";

        // Settings, lowest priority first: defaults < .jrc < env vars < -Xjr: options. config.found
        // only records whether a .jrc exists; every setting is read from config, whichever of those
        // it came from - config.load() always returns a non-null object with defaults applied.
        var config = Config.load(configPath);

        var envAutoInstall = Cstr.readEnv("JR_JAVA_AUTOINSTALL");
        if (envAutoInstall != null) {
            config.javaAutoInstall = (envAutoInstall.equals("0") || AsciiStr.equalsIgnoreCase(envAutoInstall, "false"))
                    ? 0 : 1;
        }

        // The app's arguments, verbatim, after jr's leading -Xjr: options. Mutates config for any
        // -Xjr:key=value along the way.
        var opts = JrOptions.parse(args, config);

        if (!config.logFile.isBlank()) {
            Log.init(config.logFile, config.logOverwrite);
            Log.info("Launcher started: " + exeBaseName + ".exe");
            Log.info("Config: " + configLabel(configPath, config));
            Log.info("vm.args=" + config.vmArgs);
            Log.info("java.args=" + config.javaArgs);
            Log.info("app.args=" + config.appArgs);
            Log.info("App arguments from command line: " + String.join(" ", opts.appArgs));
        }

        // Default stays java.exe so existing setups behave exactly as before
        var useJvmDll = config.useJvmDll == 1;

        var guiMode = ConsoleMode.isGuiMode();
        var hasConsole = !guiMode;
        var javaExeName = hasConsole ? "java.exe" : "javaw.exe";

        WinApi.setEnvironmentVariableA(cstr("JR_LAUNCH_MODE"), cstr(hasConsole ? "console" : "gui"));
        WinApi.setEnvironmentVariableA(cstr("JR_AOT_STATE"), cstr("off"));
        WinApi.setEnvironmentVariableA(cstr("JR_AOT_CACHE"), cstr(""));

        ConsoleMode.restoreRedirectedStdHandles();
        if (useJvmDll) {
            ConsoleMode.rebindConsoleStdStreams(hasConsole);
        }

        Log.info("Execution mode: " + (hasConsole ? "Console" : "GUI"));
        Log.info("Launch mode: " + (useJvmDll ? "in-process (jvm.dll)" : "child process (java.exe)"));
        Log.info("Java executable: " + javaExeName);

        if (opts.error != null) {
            Log.error(opts.error);
            Ui.error(hasConsole, "Invalid jr Option", opts.error);
            Log.close();
            WinApi.exit(1);
            return;
        }

        // Resource editing / signing (PRP-20 phase 2): a tool action, runs no Java at all
        if (opts.stamp.hasAction()) {
            var result = ReRun.run(opts.stamp);
            var report = result.report();
            var withNewline = report.isEmpty() || report.endsWith("\n") ? report : report + "\n";
            if (hasConsole) {
                if (result.ok()) {
                    System.out.print(withNewline);
                } else {
                    System.err.print(withNewline);
                }
            } else if (result.ok()) {
                Ui.info(false, "jr", report);
            } else {
                Ui.error(false, "jr - Error", report);
            }
            Log.close();
            WinApi.exit(result.ok() ? 0 : 1);
            return;
        }
        if (opts.stamp.hasEdits()) {
            Ui.error(hasConsole, "Invalid jr Option",
                    "Resource and signing options (-Xjr:icon, -Xjr:version..., -Xjr:sign...) need a target:\n"
                            + "-Xjr:make=<new.exe> (a copy of this exe) or -Xjr:edit=<existing.exe>.");
            Log.close();
            WinApi.exit(1);
            return;
        }

        // Nothing to run (or help asked for): show help before any Java lookup, so a bare `jr` on a
        // machine without Java never triggers the install prompt. The Java path shown here is a
        // plain PATH lookup for display only - no version check, no auto-install.
        if (opts.help || (!opts.createConfig && config.javaArgs.isBlank() && !config.hasRunTarget()
                && opts.appArgs.isEmpty())) {
            var displayJavaPath = JavaFinder.findInPath(javaExeName);
            Help.show(hasConsole, exeBaseName, javaExeName, displayJavaPath, configLabel(configPath, config),
                    useJvmDll ? 1 : 0);
            Log.close();
            WinApi.exit(opts.help ? 0 : 1);
            return;
        }

        if (config.javaArgs.isBlank() && !config.hasRunTarget() && !opts.createConfig) {
            var replacement = JrOptions.oldFlagReplacement(opts.appArgs);
            if (replacement != null) {
                var msg = "jr's --flags have been replaced by -Xjr: options, which must come before the jar.\n\n"
                        + "Use " + replacement + " instead. See -Xjr:help.";
                Ui.error(hasConsole, "Invalid jr Option", msg);
                Log.close();
                WinApi.exit(1);
                return;
            }
        }

        if (opts.createConfig) {
            handleCreateConfig(opts, configPath, hasConsole);
            return;
        }

        // A remote jar (run.url / run.maven) becomes an ordinary "-jar <cached path>" before anything
        // else looks at java.args, so AOT naming etc. work unchanged. Resolved before the Java lookup
        // so a bad hash fails before any JRE download.
        if (config.hasRunTarget()) {
            if (!config.javaArgs.isBlank()) {
                Ui.error(hasConsole, "Invalid .jrc", "Set java.args or run.url/run.maven, not both.");
                Log.close();
                WinApi.exit(1);
                return;
            }
            var jar = RemoteJar.resolve(config, hasConsole, guiMode);
            if (jar == null) {
                Log.close();
                WinApi.exit(1);
                return;
            }
            config.javaArgs = "-jar " + WinQuote.quote(jar);
        }

        var enableAOT = config.enableAOT != 0; // -1 (unset) or 1 (true) => enabled, 0 => disabled
        Log.info("AOT enabled: " + enableAOT);

        var assumeYes = opts.assumeYes || Cstr.readEnv("JR_ASSUME_YES") != null;
        var javaPath = resolveJavaPath(config, javaExeName, hasConsole, guiMode, assumeYes);
        if (javaPath == null) {
            Log.close();
            WinApi.exit(1);
            return;
        }

        // -XX:AOTCache/-XX:AOTCacheOutput exist from JDK 25 on. An older JVM refuses to start at all
        // on an option it does not know, so leave them out for it (a version that cannot be read is
        // given the benefit of the doubt).
        if (enableAOT) {
            var major = JavaFinder.detectMajorVersion(javaPath);
            if (major > 0 && major < 25) {
                enableAOT = false;
                Log.info("AOT cache skipped: Java " + major + " predates the JDK 25 AOT options");
            }
        }

        String finalCmdLine;
        if (!config.javaArgs.isBlank()) {
            Log.info("Using config-based mode");
            finalCmdLine = CmdLineBuilder.buildConfigMode(javaPath, config, opts.appArgs, enableAOT);
        } else {
            Log.info("Using traditional mode (no java.args)");
            finalCmdLine = CmdLineBuilder.buildTraditionalMode(javaPath, opts.appArgs, enableAOT);
        }
        Log.info("Final command: " + finalCmdLine);

        if (useJvmDll) {
            var jliPath = JavaFinder.findJliDll(javaPath);
            if (jliPath != null) {
                var result = JliLauncher.tryLaunch(jliPath, finalCmdLine, javaPath, guiMode);
                if (result != null) {
                    Log.info("In-process JVM exited with code: " + result);
                    Log.close();
                    WinApi.exit(result);
                    return;
                }
            } else {
                Log.warn("jli.dll not found next to " + javaPath);
            }
            Log.warn("Falling back to child process mode (java.exe)");
        }

        var result = ProcessLauncher.launch(finalCmdLine, hasConsole);
        if (!result.started) {
            Ui.error(hasConsole, "Launch Error", "Failed to launch Java process.\n\nJava: " + javaPath
                    + "\nCommand: " + finalCmdLine + "\n\nMake sure Java is properly installed.");
            Log.close();
            WinApi.exit(1);
            return;
        }
        Log.close();
        WinApi.exit(result.exitCode);
    }

    private static String configLabel(String configPath, Config config) {
        if (config.embedded) {
            return "embedded in this exe (RCDATA/JRC resource)";
        }
        return configPath + " (" + (config.found ? "found" : "not found") + ")";
    }

    private static void handleCreateConfig(JrOptions opts, String configPath, boolean hasConsole) {
        var jarPath = opts.createConfigJar.isEmpty() ? null : opts.createConfigJar;
        if (Config.createSample(configPath, jarPath)) {
            Ui.info(hasConsole, "Config Created",
                    "Created config file: " + configPath + "\n\nEdit this file to customize launcher behavior.");
            Log.close();
            WinApi.exit(0);
        } else {
            Ui.error(hasConsole, "Error", "Failed to create config file: " + configPath);
            Log.close();
            WinApi.exit(1);
        }
    }

    /** Full Java lookup: java.home (.jrc/-Xjr:) taken as is; otherwise PATH, checked against
     *  java.version (NN = exactly NN, NN+ = NN or newer) and auto-installed if missing or
     *  mismatched - mirrors launcher.c's main() Java-resolution block. */
    private static String resolveJavaPath(Config config, String javaExeName, boolean hasConsole, boolean guiMode,
            boolean assumeYes) {
        if (!config.javaHome.isEmpty()) {
            var javaPath = config.javaHome + "\\bin\\" + javaExeName;
            Log.info("Using custom Java home: " + config.javaHome);
            if (!FileIo.exists(javaPath)) {
                Ui.error(hasConsole, "Java Not Found", "Java not found at specified location:\n" + javaPath
                        + "\n\nPlease check java.home (.jrc) or -Xjr:java.home=.");
                return null;
            }
            return javaPath;
        }

        var requiredVersion = config.javaVersion > 0 ? config.javaVersion : 0;
        var requireAtLeast = requiredVersion > 0 && config.javaVersionAtLeast;

        var javaPath = JavaFinder.findInPath(javaExeName);

        // Test-only override: pretend nothing was found, so the auto-install path can be
        // exercised on a machine that already has a real JDK on PATH. Not documented in
        // --help - see prp/09-prp-java_auto_install.md.
        if (javaPath != null && Cstr.readEnv("JR_TEST_FORCE_NO_JAVA") != null) {
            Log.warn("JR_TEST_FORCE_NO_JAVA set - ignoring Java found in PATH (test mode)");
            javaPath = null;
        }

        // A Java in PATH that doesn't satisfy java.version counts as not found, so the cache
        // lookup / auto-install below gets its chance at the right one.
        String mismatch = null;
        if (javaPath != null && requiredVersion > 0) {
            var foundMajor = JavaFinder.detectMajorVersion(javaPath);
            if (foundMajor == 0) {
                Log.warn("Could not determine the version of " + javaPath + "; using it anyway");
            } else if (requireAtLeast ? foundMajor < requiredVersion : foundMajor != requiredVersion) {
                mismatch = "This application needs Java " + requiredVersion + (requireAtLeast ? " or newer" : "")
                        + ", but the Java in PATH is version " + foundMajor + ":\n" + javaPath;
                Log.info("Java " + foundMajor + " in PATH does not satisfy java.version=" + requiredVersion
                        + (requireAtLeast ? "+" : ""));
                javaPath = null;
            } else {
                Log.info("Java " + foundMajor + " in PATH satisfies java.version=" + requiredVersion
                        + (requireAtLeast ? "+" : ""));
            }
        }

        if (javaPath == null && config.javaAutoInstall != 0) { // -1 (unset) or 1 (true) => enabled
            var javaVersion = requiredVersion > 0 ? requiredVersion : 0; // 0 => JavaInstall's own default (25)
            var cacheOverride = Cstr.readEnv("JR_JDK_CACHE_DIR");
            var packageType = config.javaType.isEmpty() ? "jre" : config.javaType; // PRP-24: jre is the default
            Log.info("Attempting auto-install (" + packageType + ", version " + (requiredVersion > 0 ? requiredVersion : 25)
                    + (requireAtLeast ? "+" : "") + ")");
            var installedHome = JavaInstall.tryInstall(javaVersion, requireAtLeast, mismatch, hasConsole, guiMode,
                    assumeYes, cacheOverride, packageType);
            if (installedHome != null) {
                javaPath = installedHome + "\\bin\\" + javaExeName;
                Log.info("Using auto-installed Java: " + javaPath);
            } else {
                Log.warn("Auto-install did not complete (declined or failed)");
            }
        }

        if (javaPath == null && mismatch != null) {
            Ui.error(hasConsole, "Wrong Java Version", mismatch + "\n\nInstall Java " + requiredVersion
                    + (requireAtLeast ? " or newer" : "") + ", or set java.home in the .jrc (or "
                    + "-Xjr:java.home=C:\\path\\to\\jdk).");
            return null;
        }

        if (javaPath == null) {
            Ui.error(hasConsole, "Java Not Found", "Java not found in PATH.\n\n"
                    + "Please ensure Java is installed and added to PATH,\n"
                    + "or set java.home in the .jrc (or -Xjr:java.home=C:\\path\\to\\jdk).\n\n"
                    + "Looking for: " + javaExeName);
            return null;
        }
        Log.info("Using Java: " + javaPath);
        return javaPath;
    }
}
