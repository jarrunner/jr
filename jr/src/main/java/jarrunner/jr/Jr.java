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
        var replaced = ExeInfo.fullPath() + SelfUpdate.REPLACED; // left by -Xjr:update; gone once no instance holds it
        if (FileIo.exists(replaced)) {
            WinApi.deleteFileA(cstr(replaced));
        }
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
        ErrorReport.config = config;
        ErrorReport.javaExeName = javaExeName;

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

        if (opts.jsonDump != null || opts.checkConfig != null) {
            var code = opts.jsonDump != null ? JsonDump.run(opts.jsonDump) : JsonDump.check(opts.checkConfig);
            Log.close();
            WinApi.exit(code);
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
                    Stderr.print(withNewline);
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

        if (opts.doctor != 0) {
            var text = opts.doctor == 1 ? Doctor.report(config, configLabel(configPath, config), javaExeName)
                    : Repair.run(config, javaExeName, hasConsole, guiMode);
            if (hasConsole) {
                System.out.print(text);
            } else {
                ErrorDialog.show(opts.doctor == 1 ? "jr doctor" : "jr repair", opts.doctor == 1
                        ? "What jr would do on this computer, and why. Nothing was changed." : "What jr repaired.", text, "");
            }
            Log.close();
            WinApi.exit(0);
            return;
        }

        if (config.loadError != null) {
            var msg = "The config could not be read (" + configLabel(configPath, config) + "):\n" + config.loadError;
            Log.error(msg);
            Ui.error(hasConsole, "Invalid jr Config", msg);
            Log.close();
            WinApi.exit(1);
            return;
        }

        if (opts.update != 0) {
            int code;
            if (opts.update == 2) {
                code = SelfUpdate.run(config, hasConsole, guiMode);
            } else {
                var check = UpdateCheck.run(config);
                if (check.status == UpdateCheck.ERROR) Ui.error(hasConsole, "Update check", check.message);
                else Ui.info(hasConsole, "Update check", check.message);
                code = check.status;
            }
            Log.close();
            WinApi.exit(code);
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
        var java = JavaResolve.resolve(config, javaExeName, hasConsole, guiMode, assumeYes);
        if (java == null) {
            Log.close();
            WinApi.exit(1);
            return;
        }
        Launch.config = config;
        Launch.appArgs = opts.appArgs;
        Launch.exeName = javaExeName;
        Launch.hasConsole = hasConsole;
        Launch.guiMode = guiMode;
        Launch.run(java, enableAOT, useJvmDll);
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

}
