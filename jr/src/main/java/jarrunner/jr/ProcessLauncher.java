package jarrunner.jr;

import static jarrunner.jr.N.*;

/**
 * CreateProcessA-based child spawn, mirroring launcher.c's main() tail. STARTUPINFOA and
 * PROCESS_INFORMATION are read/written as raw byte buffers rather than via
 * org.teavm.interop.Structure - see guidelines.teavmcpp.md for why. Field offsets come from
 * WinOffsets, verified against the real compiler (see PRP-08).
 */
public final class ProcessLauncher {
    private ProcessLauncher() {}

    private static final int STARTUPINFO_SIZE = WinOffsets.STARTUPINFOA.SIZE;
    private static final int PROCESS_INFO_SIZE = WinOffsets.PROCESS_INFORMATION.SIZE;
    private static final int STARTF_USESTDHANDLES = WinApi.STARTF_USESTDHANDLES;

    public static LaunchResult launch(String cmdLine, boolean hasConsole) {
        var si = alloc(STARTUPINFO_SIZE);
        WinOffsets.STARTUPINFOA.cb(si, STARTUPINFO_SIZE);

        if (hasConsole) {
            WinOffsets.STARTUPINFOA.dwFlags(si, STARTF_USESTDHANDLES);
            WinOffsets.STARTUPINFOA.hStdInput(si, WinApi.getStdHandle(WinApi.STD_INPUT_HANDLE));
            WinOffsets.STARTUPINFOA.hStdOutput(si, WinApi.getStdHandle(WinApi.STD_OUTPUT_HANDLE));
            WinOffsets.STARTUPINFOA.hStdError(si, WinApi.getStdHandle(WinApi.STD_ERROR_HANDLE));
        }

        var pi = alloc(PROCESS_INFO_SIZE);

        var ok = WinApi.createProcessA(NULL, cstr(cmdLine), NULL,
                NULL, 1, 0, NULL, NULL, si, pi) != 0;

        if (!ok) {
            return new LaunchResult(false, -1);
        }

        var hProcess = WinOffsets.PROCESS_INFORMATION.hProcess(pi);
        var hThread = WinOffsets.PROCESS_INFORMATION.hThread(pi);
        var pid = WinOffsets.PROCESS_INFORMATION.dwProcessId(pi);
        Log.info("Java process started successfully (PID: " + pid + ")");

        if (!hasConsole) {
            WinApi.closeHandle(hProcess);
            WinApi.closeHandle(hThread);
            Log.info("Launched in GUI mode, launcher exiting");
            return new LaunchResult(true, 0);
        }

        WinApi.waitForSingleObject(hProcess, WinApi.INFINITE);
        var exitCodeVar = intVar();
        WinApi.getExitCodeProcess(hProcess, exitCodeVar);
        var exitCode = exitCodeVar.getInt();
        Log.info("Java process exited with code: " + exitCode);

        WinApi.closeHandle(hProcess);
        WinApi.closeHandle(hThread);
        return new LaunchResult(true, exitCode);
    }

    /** Spawns cmdLine hidden (no console flash) and waits for it - for internal tool invocations
     *  like tar.exe during auto-install (PRP-09). Unlike launch(), always waits regardless of
     *  console/GUI mode, and never inherits/redirects standard handles. */
    public static boolean runHiddenAndWait(String cmdLine) {
        var si = alloc(STARTUPINFO_SIZE);
        WinOffsets.STARTUPINFOA.cb(si, STARTUPINFO_SIZE);

        var pi = alloc(PROCESS_INFO_SIZE);

        var ok = WinApi.createProcessA(NULL, cstr(cmdLine), NULL,
                NULL, 0, WinApi.CREATE_NO_WINDOW, NULL, NULL,
                si, pi) != 0;
        if (!ok) {
            return false;
        }

        var hProcess = WinOffsets.PROCESS_INFORMATION.hProcess(pi);
        var hThread = WinOffsets.PROCESS_INFORMATION.hThread(pi);
        WinApi.waitForSingleObject(hProcess, WinApi.INFINITE);
        var exitCodeVar = intVar();
        WinApi.getExitCodeProcess(hProcess, exitCodeVar);
        var exitCode = exitCodeVar.getInt();
        WinApi.closeHandle(hProcess);
        WinApi.closeHandle(hThread);
        return exitCode == 0;
    }
}
