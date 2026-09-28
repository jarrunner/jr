package pocapp.jr;

import org.teavm.interop.Address;

/**
 * Runs the JVM inside this process via jli.dll (JLI_Launch), instead of spawning java.exe as a
 * child - see WinApi/guidelines.teavmcpp.md for the GetProcAddress-to-Function cast this depends
 * on. Mirrors launcher.c's launchInProcess. Returns null if in-process setup could not go ahead
 * (caller falls back to spawning java.exe), otherwise the application's real exit code.
 */
public final class JliLauncher {
    private JliLauncher() {}

    private static final int STD_ARG_STRIDE = 16; // {char* arg; unsigned char has_wildcard;}, padded

    public static Integer tryLaunch(String jliPath, String cmdLine, String expectedArgv0, boolean guiMode) {
        var jli = WinApi.loadLibraryExA(Cstr.of(jliPath), Address.fromInt(0), WinApi.LOAD_WITH_ALTERED_SEARCH_PATH);
        if (jli.toLong() == 0) {
            Log.warn("Could not load " + jliPath);
            return null;
        }

        var launchAddr = WinApi.getProcAddress(jli, Cstr.of("JLI_Launch"));
        var cmdToArgsAddr = WinApi.getProcAddress(jli, Cstr.of("JLI_CmdToArgs"));
        var getStdArgcAddr = WinApi.getProcAddress(jli, Cstr.of("JLI_GetStdArgc"));
        var getStdArgsAddr = WinApi.getProcAddress(jli, Cstr.of("JLI_GetStdArgs"));
        if (launchAddr.toLong() == 0 || cmdToArgsAddr.toLong() == 0
                || getStdArgcAddr.toLong() == 0 || getStdArgsAddr.toLong() == 0) {
            Log.warn(jliPath + " does not export the expected JLI entry points");
            return null;
        }

        var launch = (JliLaunchFn) (Object) launchAddr;
        var cmdToArgs = (JliCmdToArgsFn) (Object) cmdToArgsAddr;
        var getStdArgc = (JliGetStdArgcFn) (Object) getStdArgcAddr;
        var getStdArgs = (JliGetStdArgsFn) (Object) getStdArgsAddr;

        cmdToArgs.invoke(Cstr.of(cmdLine));
        var margc = getStdArgc.invoke();
        var stdArgsBase = getStdArgs.invoke();
        if (margc < 1 || stdArgsBase.toLong() == 0) {
            Log.warn("JLI_CmdToArgs produced no arguments");
            return null;
        }

        var argv0 = Cstr.read(stdArgsBase.add(0).getAddress());
        if (argv0 == null || !argv0.equals(expectedArgv0)) {
            Log.warn("Unexpected JLI argv[0] '" + argv0 + "' (wanted '" + expectedArgv0
                    + "') - not using in-process JVM");
            return null;
        }

        var margv = new byte[(margc + 1) * 8];
        var margvAddr = Address.ofData(margv);
        for (var i = 0; i < margc; i++) {
            margvAddr.add(i * 8).putAddress(stdArgsBase.add(i * STD_ARG_STRIDE).getAddress());
        }
        margvAddr.add(margc * 8).putAddress(Address.fromInt(0));

        Log.info("Invoking in-process JVM via " + jliPath + " (" + margc + " args)");
        return launch.invoke(margc, margvAddr, 0, Address.fromInt(0), 0, Address.fromInt(0),
                Cstr.of("jr"), Cstr.of("jr"), Cstr.of("java"), Cstr.of("java"),
                (byte) 0, (byte) 1, (byte) (guiMode ? 1 : 0), 0);
    }
}
