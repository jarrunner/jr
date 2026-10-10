package jarrunner.jr;

import org.teavm.interop.Address;

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
        args = commandLineArgs();
        if (Checks.ON && args.length == 1 && args[0].equals("-Xjr:checks-selftest")) { // checks builds only (PRP-35)
            Buf.alloc(4).getInt(2); // 4 bytes at offset 2 of a 4-byte buffer: must throw
        }
        var replaced = ExeInfo.fullPath() + SelfUpdate.REPLACED; // left by -Xjr:update; gone once no instance holds it
        if (FileIo.exists(replaced)) {
            WinApi.deleteFileW(replaced);
        }
        var exeBaseName = ExeInfo.baseNameNoExt();

        // Settings, lowest priority first: defaults < the baked-in jrc-json < env vars < -Xjr: options.
        // Every setting is read from config, whichever of those it came from.
        var config = Config.load();

        var envAutoInstall = Cstr.readEnv("JR_JAVA_AUTOINSTALL");
        if (envAutoInstall != null) {
            config.javaAutoInstall = (envAutoInstall.equals("0") || AsciiStr.equalsIgnoreCase(envAutoInstall, "false"))
                    ? 0 : 1;
        }

        // The app's arguments, verbatim, after jr's leading -Xjr: options. Mutates config for any
        // -Xjr:key=value along the way.
        var opts = JrOptions.parse(args, config);
        Ui.batch = opts.batch;

        if (!config.logFile.isBlank()) {
            Log.init(config.logFile, config.logOverwrite);
            Log.info("Launcher started: " + exeBaseName + ".exe");
            Log.info("Config: " + configLabel(config));
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

        Cstr.setEnv("JR_LAUNCH_MODE", hasConsole ? "console" : "gui");
        Cstr.setEnv("JR_AOT_STATE", "off");
        Cstr.setEnv("JR_AOT_CACHE", "");

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
                    Stderr.out(withNewline);
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
            var text = opts.doctor == 1 ? Doctor.report(config, configLabel(config), javaExeName)
                    : Repair.run(config, javaExeName, hasConsole, guiMode);
            if (hasConsole) {
                Stderr.out(text);
            } else {
                ErrorDialog.show(opts.doctor == 1 ? "jr doctor" : "jr repair", opts.doctor == 1
                        ? "What jr would do on this computer, and why. Nothing was changed." : "What jr repaired.", text, "");
            }
            Log.close();
            WinApi.exit(0);
            return;
        }

        if (config.loadError != null) {
            var msg = "The config could not be read (" + configLabel(config) + "):\n" + config.loadError;
            Log.error(msg);
            Ui.error(hasConsole, "Invalid jr Config", msg);
            Log.close();
            WinApi.exit(1);
            return;
        }

        if (opts.update != 0) {
            int code;
            String status, latest, said;
            if (opts.update == 2) {
                code = opts.batch ? SelfUpdate.run(config, true, false) : SelfUpdate.run(config, hasConsole, guiMode);
                status = SelfUpdate.status;
                latest = SelfUpdate.latest;
                said = SelfUpdate.said;
            } else {
                var check = UpdateCheck.run(config);
                if (check.status == UpdateCheck.ERROR) Ui.error(hasConsole, "Update check", check.message);
                else Ui.info(hasConsole, "Update check", check.message);
                code = check.status;
                status = code == UpdateCheck.NEWER ? UpdateResult.NEWER : code == UpdateCheck.CURRENT ? UpdateResult.CURRENT : UpdateResult.ERROR;
                latest = check.release == null ? null : check.release.get("version").str();
                said = check.message;
            }
            if (opts.batch) Stderr.out(UpdateResult.json(status, config.appVersion, latest, said));
            Log.close();
            WinApi.exit(code);
            return;
        }

        // Nothing to run (or help asked for): show help before any Java lookup, so a bare `jr` on a
        // machine without Java never triggers the install prompt. The Java path shown here is a
        // plain PATH lookup for display only - no version check, no auto-install.
        if (opts.help || (config.javaArgs.isBlank() && !config.hasRunTarget()
                && opts.appArgs.isEmpty())) {
            var displayJavaPath = JavaFinder.findInPath(javaExeName);
            Help.show(hasConsole, exeBaseName, javaExeName, displayJavaPath, configLabel(config),
                    useJvmDll ? 1 : 0);
            Log.close();
            WinApi.exit(opts.help ? 0 : 1);
            return;
        }

        if (config.javaArgs.isBlank() && !config.hasRunTarget()) {
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

        // A remote jar (run.url / run.maven) becomes an ordinary "-jar <cached path>" before anything
        // else looks at java.args, so AOT naming etc. work unchanged. Resolved before the Java lookup
        // so a bad hash fails before any JRE download.
        if (config.hasRunTarget()) {
            if (!config.javaArgs.isBlank()) {
                Ui.error(hasConsole, "Invalid jr Config", "Set java.args or run.url/run.maven, not both.");
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

    /** The arguments as typed, from GetCommandLineW (PRP-34). TeaVM's main(String[]) is built from the C argv,
     *  which Windows hands over in the ANSI code page: a name in Cyrillic or Devanagari, or an emoji, arrives as
     *  '?'. CommandLineToArgvW splits the same way the CRT does for ordinary quoting; argv[0] is this exe. */
    @Unsafe("trusts CommandLineToArgvW's count n for the length of the pointer array it returns")
    private static String[] commandLineArgs() {
        return memScoped(() -> {
            var count = intVar();
            var argv = WinApi.commandLineToArgvW(WinApi.getCommandLineW(), count);
            var n = argv.toLong() == 0 ? 0 : intOf(count);
            var array = Buf.wrap(argv, n * Address.sizeOf());
            var out = new String[Math.max(n - 1, 0)];
            for (var i = 1; i < n; i++) {
                out[i - 1] = wstring(array.getAddress(i * Address.sizeOf()));
            }
            if (argv.toLong() != 0) WinApi.localFree(argv);
            return out;
        });
    }

    private static String configLabel(Config config) {
        return config.embedded ? "embedded in this exe (RCDATA/JRC resource)" : "none baked in";
    }
}
