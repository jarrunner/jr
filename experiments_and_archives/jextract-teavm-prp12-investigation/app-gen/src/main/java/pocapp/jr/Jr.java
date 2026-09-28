package pocapp.jr;

/**
 * Java Runner (jr) - full port to TeaVM's C backend, mirroring launcher.c feature-for-feature.
 * See ../../../../prp/07-prp.02.step1-findings.md for the size/behaviour comparison against the
 * hand-written C original, and guidelines.teavmcpp.md for every toolchain gotcha hit building this.
 */
public final class Jr {
    public static void main(String[] args) {
        Timing.init();
        var exeBaseName = ExeInfo.baseNameNoExt();
        var configPath = ExeInfo.fullPathNoExt() + ".jrc";
        var config = Config.load(configPath);

        if (config != null && !config.logFile.isBlank()) {
            Log.init(config.logFile, config.logOverwrite);
            Log.info("Launcher started: " + exeBaseName + ".exe");
        }

        var useJvmDll = 0;
        if (ArgsFilter.has(args, "--jvm-dll")) {
            useJvmDll = 1;
        } else if (ArgsFilter.has(args, "--jvm-exe")) {
            useJvmDll = 0;
        } else if (config != null && config.useJvmDll != -1) {
            useJvmDll = config.useJvmDll;
        }

        var guiMode = ConsoleMode.isGuiMode();
        var hasConsole = !guiMode;
        var javaExeName = hasConsole ? "java.exe" : "javaw.exe";

        WinApi.setEnvironmentVariableA(Cstr.of("JR_LAUNCH_MODE"), Cstr.of(hasConsole ? "console" : "gui"));
        ConsoleMode.restoreRedirectedStdHandles();
        if (useJvmDll == 1) {
            ConsoleMode.rebindConsoleStdStreams(hasConsole);
        }

        Log.info("Execution mode: " + (hasConsole ? "Console" : "GUI"));
        Log.info("Launch mode: " + (useJvmDll == 1 ? "in-process (jvm.dll)" : "child process (java.exe)"));
        Log.info("Java executable: " + javaExeName);

        if (ArgsFilter.has(args, "--create-config")) {
            handleCreateConfig(args, configPath, hasConsole);
            return;
        }

        var enableAOT = true;
        if (ArgsFilter.has(args, "--disable-aot")) {
            enableAOT = false;
        } else if (ArgsFilter.has(args, "--enable-aot")) {
            enableAOT = true;
        } else if (config != null && config.enableAOT != -1) {
            enableAOT = config.enableAOT == 1;
        }
        Log.info("AOT enabled: " + enableAOT);

        var javaPath = resolveJavaPath(args, config, javaExeName, hasConsole, guiMode);
        if (javaPath == null) {
            Log.close();
            WinApi.exit(1);
            return;
        }

        var extraArgs = ArgsFilter.stripLauncherFlags(args);
        String finalCmdLine;
        if (config != null && !config.javaArgs.isBlank()) {
            Log.info("Using config-based mode");
            finalCmdLine = CmdLineBuilder.buildConfigMode(javaPath, config, extraArgs, enableAOT);
        } else {
            Log.info("Using traditional mode (no config file)");
            if (extraArgs.isEmpty()) {
                Help.show(hasConsole, exeBaseName, javaExeName, javaPath, configPath, useJvmDll);
                Log.close();
                WinApi.exit(1);
                return;
            }
            finalCmdLine = CmdLineBuilder.buildTraditionalMode(javaPath, extraArgs, enableAOT);
        }
        Log.info("Final command: " + finalCmdLine);

        if (useJvmDll == 1) {
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

    private static void handleCreateConfig(String[] args, String configPath, boolean hasConsole) {
        var jarPath = ArgsFilter.createConfigJarArg(args);
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

    private static String resolveJavaPath(String[] args, Config config, String javaExeName, boolean hasConsole,
            boolean guiMode) {
        var javaHome = ArgsFilter.javaHome(args);
        if (javaHome != null) {
            var javaPath = javaHome + "\\bin\\" + javaExeName;
            Log.info("Using custom Java home: " + javaHome);
            if (!FileIo.exists(javaPath)) {
                Ui.error(hasConsole, "Java Not Found", "Java not found at specified location:\n" + javaPath
                        + "\n\nPlease check your --java-home path.");
                return null;
            }
            return javaPath;
        }

        var javaPath = JavaFinder.findInPath(javaExeName);

        // Test-only override: pretend nothing was found, so the auto-install path can be
        // exercised on a machine that already has a real JDK on PATH. Not documented in
        // --help - see prp/09-prp-java_auto_install.md.
        if (javaPath != null && Cstr.readEnv("JR_TEST_FORCE_NO_JAVA") != null) {
            Log.warn("JR_TEST_FORCE_NO_JAVA set - ignoring Java found in PATH (test mode)");
            javaPath = null;
        }

        if (javaPath == null && autoInstallEnabled(config)) {
            var javaVersion = config != null && config.javaVersion > 0 ? config.javaVersion : 0;
            var assumeYes = ArgsFilter.has(args, "--yes") || Cstr.readEnv("JR_ASSUME_YES") != null;
            var cacheOverride = Cstr.readEnv("JR_JDK_CACHE_DIR");
            Log.info("Java not found; attempting auto-install (version " + javaVersion + ")");
            var installedHome = JavaInstall.tryInstall(javaVersion, hasConsole, guiMode, assumeYes, cacheOverride);
            if (installedHome != null) {
                javaPath = installedHome + "\\bin\\" + javaExeName;
                Log.info("Using auto-installed Java: " + javaPath);
            } else {
                Log.warn("Auto-install did not complete (declined or failed)");
            }
        }

        if (javaPath == null) {
            Ui.error(hasConsole, "Java Not Found", "Java not found in PATH.\n\n"
                    + "Please ensure Java is installed and added to PATH,\n"
                    + "or use --java-home=C:\\path\\to\\jdk to specify location.\n\n"
                    + "Looking for: " + javaExeName);
            return null;
        }
        Log.info("Using Java: " + javaPath);
        return javaPath;
    }

    private static boolean autoInstallEnabled(Config config) {
        var env = Cstr.readEnv("JR_JAVA_AUTOINSTALL");
        if (env != null) {
            return !(env.equals("0") || AsciiStr.equalsIgnoreCase(env, "false"));
        }
        if (config != null && config.javaAutoInstall != -1) {
            return config.javaAutoInstall == 1;
        }
        return true;
    }
}
