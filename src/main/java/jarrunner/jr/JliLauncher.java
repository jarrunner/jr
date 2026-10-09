package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/**
 * Runs the JVM inside this process via jli.dll (JLI_Launch), instead of spawning java.exe as a
 * child - see WinApi/guidelines.teavmcpp.md for the GetProcAddress-to-Function cast this depends
 * on. Mirrors launcher.c's launchInProcess. Returns null if in-process setup could not go ahead
 * (caller falls back to spawning java.exe), otherwise the application's real exit code.
 */
public final class JliLauncher {
    private JliLauncher() {}

    private static final int STD_ARG_STRIDE = 16; // {char* arg; unsigned char has_wildcard;}, padded

    @Unsafe("trusts that jli.dll's JLI_Launch, JLI_CmdToArgs, JLI_GetStdArgc and JLI_GetStdArgs have the signatures the Jli*Fn classes declare")
    public static Integer tryLaunch(String jliPath, String cmdLine, String expectedArgv0, boolean guiMode) {
        var jli = WinApi.loadLibraryExW(jliPath, NULL, WinApi.LOAD_WITH_ALTERED_SEARCH_PATH);
        if (jli.toLong() == 0) {
            Log.warn("Could not load " + jliPath);
            return null;
        }
        handOver(jli); // stays loaded for the life of the process: the JVM runs from it, and a failed probe below leaves it as before

        var launchAddr = WinApi.getProcAddress(jli, "JLI_Launch");
        var cmdToArgsAddr = WinApi.getProcAddress(jli, "JLI_CmdToArgs");
        var getStdArgcAddr = WinApi.getProcAddress(jli, "JLI_GetStdArgc");
        var getStdArgsAddr = WinApi.getProcAddress(jli, "JLI_GetStdArgs");
        if (launchAddr.toLong() == 0 || cmdToArgsAddr.toLong() == 0
                || getStdArgcAddr.toLong() == 0 || getStdArgsAddr.toLong() == 0) {
            Log.warn(jliPath + " does not export the expected JLI entry points");
            return null;
        }

        var launch = (JliLaunchFn) (Object) launchAddr;
        var cmdToArgs = (JliCmdToArgsFn) (Object) cmdToArgsAddr;
        var getStdArgc = (JliGetStdArgcFn) (Object) getStdArgcAddr;
        var getStdArgs = (JliGetStdArgsFn) (Object) getStdArgsAddr;

        // JLI_CmdToArgs and JLI_Launch take char* in the ANSI code page: java.exe passes GetCommandLineA, and
        // the JVM reads every path it is given the same way. Text outside the code page cannot reach the JVM
        // through either door (PRP-34); with the process code page set to UTF-8 by the exe manifest, all of it can.
        if (!fitsCodePage(cmdLine, WinApi.getACP())) {
            Log.warn("The command line has characters outside the ANSI code page (" + WinApi.getACP()
                    + "); the JVM will see them as '?'. An exe manifest with activeCodePage UTF-8 lifts this.");
        }
        cmdToArgs.invoke(acp(cmdLine));
        var margc = getStdArgc.invoke();
        var stdArgsBase = getStdArgs.invoke();
        if (margc < 1 || stdArgsBase.toLong() == 0) {
            Log.warn("JLI_CmdToArgs produced no arguments");
            return null;
        }

        var argv0 = acpString(ptrOf(stdArgsBase));
        if (argv0 == null || !argv0.equals(expectedArgv0)) {
            Log.warn("Unexpected JLI argv[0] '" + argv0 + "' (wanted '" + expectedArgv0
                    + "') - not using in-process JVM");
            return null;
        }

        var margv = argv(stdArgsBase, margc);

        Log.info("Invoking in-process JVM via " + jliPath + " (" + margc + " args)");
        return launch.invoke(margc, margv, 0, NULL, 0, NULL,
                ascii("jr"), ascii("jr"), ascii("java"), ascii("java"),
                (byte) 0, (byte) 1, (byte) (guiMode ? 1 : 0), 0);
    }

    /** JLI_Launch wants a NULL-terminated char*[]; JLI_GetStdArgs gives StdArg[argc], each {char* arg; bool
     *  has_wildcard}, STD_ARG_STRIDE bytes apart. */
    @Unsafe("trusts JLI_GetStdArgc's count for the length of the StdArg array JLI_GetStdArgs returns")
    private static Address argv(Address stdArgs, int argc) {
        var p = Address.sizeOf();
        var std = Buf.wrap(stdArgs, argc * STD_ARG_STRIDE);
        var margv = Buf.alloc((argc + 1) * p);
        for (var i = 0; i < argc; i++) {
            margv.putAddress(i * p, std.getAddress(i * STD_ARG_STRIDE));
        }
        margv.putAddress(argc * p, NULL);
        return margv.ptr();
    }
}
