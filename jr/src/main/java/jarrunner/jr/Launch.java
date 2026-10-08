package jarrunner.jr;

import java.util.List;

import static jarrunner.jr.N.*;

/** PRP-31: runs the app, and when the JVM fails to START (its own error, before any app code ran) retries
 *  once per step, in order: without the AOT cache, with the next suitable Java found, with a downloaded
 *  copy of the preferred Java. Retries always run as a child process. An app that ran and exited non-zero
 *  is the app's business; only in GUI mode, where nobody can see stderr, is an early failure shown. */
public final class Launch {
    private Launch() {}

    private static final int GUI_WAIT_MS = 10_000;

    static Config config;
    static List<String> appArgs;
    static String exeName;
    static boolean hasConsole, guiMode;
    static JavaRange range;
    static JavaChooser chooser;    // null when java.home was given explicitly
    private static JavaHome current;
    private static boolean currentAot, downloaded;
    private static String cmdLine = "";
    private static final StringBuilder tried = new StringBuilder();

    /** Never returns. */
    static void run(JavaHome java, boolean enableAOT, boolean inProcess) {
        var r = attempt(java, enableAOT, inProcess);
        finish(r, StartCapture.end(hasConsole));
    }

    private static LaunchResult attempt(JavaHome java, boolean enableAOT, boolean inProcess) {
        current = java;
        currentAot = enableAOT && java.major >= JavaRange.AOT_MIN;
        if (enableAOT && !currentAot) {
            Log.info("AOT cache skipped: Java " + (java.major > 0 ? java.major + " predates" : "of unknown version cannot use")
                    + " the JDK 25 AOT options");
        }
        if (currentAot && !fitsCodePage(java.home, systemAcp())) {
            // PRP-34, measured with JDK 25: from a home outside the system code page the JVM cannot open its own
            // lib\modules when given an AOT cache, and the cache-writing java.exe it spawns itself fails the same way
            // even under a UTF-8 jvm=dll, so every launch would pay for a training run that never produces a cache.
            Log.info("AOT cache skipped: the JDK cannot use one from a folder outside the ANSI code page: " + java.home);
            currentAot = false;
        }
        AotCache.jvmTag = AotCache.jvmTag(java);
        var javaPath = CmdLineBuilder.forJava(java.javaExe(exeName));
        cmdLine = config.javaArgs.isBlank() ? CmdLineBuilder.buildTraditionalMode(javaPath, appArgs, currentAot)
                : CmdLineBuilder.buildConfigMode(javaPath, config, appArgs, currentAot);
        Log.info("Final command: " + cmdLine);
        tried.append("- Java ").append(java.major).append(" at ").append(java.home).append(currentAot ? ", with AOT cache" : "")
                .append(inProcess ? ", in-process" : ", child process").append('\n');

        var stderr = StartCapture.begin(hasConsole);
        var owned = stderr.toLong() != 0;
        if (inProcess && !java.jli.isEmpty()) {
            if (owned) {
                ConsoleMode.bindStderr(stderr); // takes the handle; a child fallback inherits fd 2's instead
                StartCapture.bindJdkStderr();
                stderr = WinApi.getStdHandle(WinApi.STD_ERROR_HANDLE);
                owned = false;
            }
            ExitHook.install();
            var code = JliLauncher.tryLaunch(java.jli, cmdLine, javaPath, guiMode && StartCapture.file.isEmpty());
            ExitHook.armed = false;
            if (code != null) {
                Log.info("In-process JVM exited with code: " + code);
                return new LaunchResult(true, code);
            }
            Log.warn("Falling back to child process mode (java.exe)");
        } else if (inProcess) {
            Log.warn("No in-process JVM for " + java.home + " (no jli.dll, or a different architecture from this exe)");
        }
        if (!fitsCodePage(cmdLine, systemAcp())) {
            // PRP-34: jr passes the command line as UTF-16, but java.exe reads it through GetCommandLineA, and the
            // JVM finds its own files the same way. Nothing jr can send gets past that.
            var note = "  The command line has characters outside this computer's ANSI code page (" + systemAcp()
                    + "): java.exe receives them as '?'.\n";
            Log.warn(note.strip());
            tried.append(note);
        }
        var r = ProcessLauncher.launch(cmdLine, hasConsole, stderr, GUI_WAIT_MS);
        if (owned) {
            WinApi.closeHandle(stderr);
        }
        return r;
    }

    /** Called through ExitHook when jli.dll (or the app) calls exit() during an in-process run. */
    static void onJvmExit() {
        var text = StartCapture.end(hasConsole);
        if (!jvmWasCreated()) {
            Log.warn("The in-process JVM failed to start");
            heal(text);
        }
    }

    /** False when no JVM exists in this process: jvm.dll never loaded, or JNI_CreateJavaVM failed. */
    @Unsafe("trusts that jvm.dll's JNI_GetCreatedJavaVMs has the signature JniGetCreatedVmsFn declares")
    private static boolean jvmWasCreated() {
        var jvm = WinApi.getModuleHandleW("jvm.dll");
        var fn = jvm.toLong() == 0 ? NULL : WinApi.getProcAddress(jvm, "JNI_GetCreatedJavaVMs");
        if (fn.toLong() == 0) {
            return jvm.toLong() != 0;
        }
        var n = intVar();
        ((JniGetCreatedVmsFn) (Object) fn).invoke(ptrVar(), 1, n);
        return intOf(n) > 0;
    }

    private static void finish(LaunchResult r, String text) {
        if (!r.started) {
            fail("Java could not be started.", "Windows refused to start " + current.javaExe(exeName) + ".", text);
        }
        if (r.detached || r.exitCode == 0) {
            done(r.exitCode);
        }
        if (StartFailure.jvmDidNotStart(text)) {
            heal(text);
        }
        if (guiMode && StartCapture.elapsedMillis() < GUI_WAIT_MS && !text.isBlank()) {
            fail("The app stopped right after it started (exit code " + r.exitCode + ").", null, text);
        }
        done(r.exitCode);
    }

    /** Retries until one start succeeds, then exits with that run's code; shows the error when nothing is left. */
    private static void heal(String firstText) {
        var text = firstText;
        while (true) {
            var option = StartFailure.rejectedOption(text);
            var next = currentAot ? current : nextJava();
            if (next == null) {
                var why = option != null ? "Java refused the option " + option + ", which comes from "
                        + StartFailure.sourceOf(option, config, String.join(" ", appArgs)) + "." : "Java could not start.";
                fail(why, null, text);
            }
            if (next == current) {
                AotCache.deleteLast();
            }
            Stderr.println("jr: Java did not start" + (option != null ? " (it refused " + option + ")" : "") + "; trying "
                    + (next == current ? "again without the AOT cache." : "Java " + next.major + " at " + next.home + "."));
            var r = attempt(next, false, false);
            text = StartCapture.end(hasConsole);
            if (r.started && !StartFailure.jvmDidNotStart(text)) {
                Log.info("Started after retrying with Java " + next.major + " at " + next.home);
                done(r.detached ? 0 : r.exitCode);
            }
        }
    }

    /** Another Java the rules allow, not yet tried; then a download of the preferred one. */
    private static JavaHome nextJava() {
        if (chooser == null) {
            return null;
        }
        JavaHome best = null;
        for (var h : chooser.seen) {
            if (h.usable() && range.accepts(h.major) && tried.indexOf(" at " + h.home + ",") < 0
                    && (best == null || Math.abs(h.major - range.preferred) < Math.abs(best.major - range.preferred))) {
                best = h;
            }
        }
        if (best == null && !downloaded && config.javaAutoInstall != 0) {
            downloaded = true;
            var home = JavaInstall.tryInstall(range.preferred, false, "Java did not start with the Java versions found on this computer.",
                    hasConsole, guiMode, false, JavaInstall.cacheRoot(), chooser.packageType);
            var h = home == null ? null : JavaHome.inspect(home, "downloaded", exeName);
            best = h != null && h.usable() && tried.indexOf(" at " + h.home + ",") < 0 ? h : null;
        }
        return best;
    }

    private static void fail(String summary, String detail, String captured) {
        Ui.error(hasConsole, "Java Did Not Start", summary + (detail != null ? "\n\n" + detail : "")
                + "\n\njr tried:\n" + tried + (captured.isBlank() ? "" : "\nJava printed:\n" + captured));
        done(1);
    }

    private static void done(int code) {
        Log.close();
        WinApi.exitProcess(code);
    }
}
