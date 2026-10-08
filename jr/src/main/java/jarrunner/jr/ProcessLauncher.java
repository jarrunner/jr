package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/**
 * CreateProcessW-based child spawn, mirroring launcher.c's main() tail. STARTUPINFOW and
 * PROCESS_INFORMATION are read/written as raw byte buffers rather than via
 * org.teavm.interop.Structure - see guidelines.teavmcpp.md for why. Field offsets come from
 * WinOffsets, verified against the real compiler (see PRP-08).
 */
public final class ProcessLauncher {
    private ProcessLauncher() {}

    private static final int STARTUPINFO_SIZE = WinOffsets.STARTUPINFOW.SIZE;
    private static final int PROCESS_INFO_SIZE = WinOffsets.PROCESS_INFORMATION.SIZE;
    private static final int STARTF_USESTDHANDLES = WinApi.STARTF_USESTDHANDLES;

    /** GUI mode: stderr goes to guiStderr (PRP-31 capture, NULL for none), and jr waits up to guiWaitMs so
     *  that a start failure can be reported; a process still running then is left to run (detached). */
    public static LaunchResult launch(String cmdLine, boolean hasConsole, Address guiStderr, int guiWaitMs) {
        var si = alloc(STARTUPINFO_SIZE);
        WinOffsets.STARTUPINFOW.cb(si, STARTUPINFO_SIZE);

        if (hasConsole) {
            WinOffsets.STARTUPINFOW.dwFlags(si, STARTF_USESTDHANDLES);
            WinOffsets.STARTUPINFOW.hStdInput(si, WinApi.getStdHandle(WinApi.STD_INPUT_HANDLE));
            WinOffsets.STARTUPINFOW.hStdOutput(si, WinApi.getStdHandle(WinApi.STD_OUTPUT_HANDLE));
            WinOffsets.STARTUPINFOW.hStdError(si, WinApi.getStdHandle(WinApi.STD_ERROR_HANDLE));
        } else if (guiStderr.toLong() != 0) {
            WinOffsets.STARTUPINFOW.dwFlags(si, STARTF_USESTDHANDLES);
            WinOffsets.STARTUPINFOW.hStdError(si, guiStderr);
        }

        var pi = alloc(PROCESS_INFO_SIZE);

        var ok = WinApi.createProcessW(NULL, wcstr(cmdLine), NULL,
                NULL, 1, 0, NULL, NULL, si, pi) != 0;

        if (!ok) {
            return new LaunchResult(false, -1);
        }

        var hProcess = WinOffsets.PROCESS_INFORMATION.hProcess(pi);
        var hThread = WinOffsets.PROCESS_INFORMATION.hThread(pi);
        var pid = WinOffsets.PROCESS_INFORMATION.dwProcessId(pi);
        Log.info("Java process started successfully (PID: " + pid + ")");

        if (!hasConsole && WinApi.waitForSingleObject(hProcess, guiWaitMs) == WinApi.WAIT_TIMEOUT) {
            WinApi.closeHandle(hProcess);
            WinApi.closeHandle(hThread);
            Log.info("Launched in GUI mode and still running, launcher exiting");
            var r = new LaunchResult(true, 0);
            r.detached = true;
            return r;
        }

        WinApi.waitForSingleObject(hProcess, WinApi.INFINITE);
        var exitCodeVar = intVar();
        WinApi.getExitCodeProcess(hProcess, exitCodeVar);
        var exitCode = intOf(exitCodeVar);
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
        WinOffsets.STARTUPINFOW.cb(si, STARTUPINFO_SIZE);

        var pi = alloc(PROCESS_INFO_SIZE);

        var ok = WinApi.createProcessW(NULL, wcstr(cmdLine), NULL,
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
        var exitCode = intOf(exitCodeVar);
        WinApi.closeHandle(hProcess);
        WinApi.closeHandle(hThread);
        return exitCode == 0;
    }
}
