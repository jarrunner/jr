package pocapp.jr;

import org.teavm.interop.Address;

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
    private static final int STARTF_USESTDHANDLES = 0x00000100;

    public static LaunchResult launch(String cmdLine, boolean hasConsole) {
        var si = new byte[STARTUPINFO_SIZE];
        var siAddr = Address.ofData(si);
        siAddr.add(WinOffsets.STARTUPINFOA.cb).putInt(STARTUPINFO_SIZE);

        if (hasConsole) {
            siAddr.add(WinOffsets.STARTUPINFOA.dwFlags).putInt(STARTF_USESTDHANDLES);
            siAddr.add(WinOffsets.STARTUPINFOA.hStdInput).putAddress(WinApi.getStdHandle(WinApi.STD_INPUT_HANDLE));
            siAddr.add(WinOffsets.STARTUPINFOA.hStdOutput).putAddress(WinApi.getStdHandle(WinApi.STD_OUTPUT_HANDLE));
            siAddr.add(WinOffsets.STARTUPINFOA.hStdError).putAddress(WinApi.getStdHandle(WinApi.STD_ERROR_HANDLE));
        }

        var pi = new byte[PROCESS_INFO_SIZE];
        var piAddr = Address.ofData(pi);

        var ok = WinApi.createProcessA(Address.fromInt(0), Cstr.of(cmdLine), Address.fromInt(0),
                Address.fromInt(0), 1, 0, Address.fromInt(0), Address.fromInt(0), siAddr, piAddr) != 0;

        if (!ok) {
            return new LaunchResult(false, -1);
        }

        var hProcess = piAddr.add(WinOffsets.PROCESS_INFORMATION.hProcess).getAddress();
        var hThread = piAddr.add(WinOffsets.PROCESS_INFORMATION.hThread).getAddress();
        var pid = piAddr.add(WinOffsets.PROCESS_INFORMATION.dwProcessId).getInt();
        Log.info("Java process started successfully (PID: " + pid + ")");

        if (!hasConsole) {
            WinApi.closeHandle(hProcess);
            WinApi.closeHandle(hThread);
            Log.info("Launched in GUI mode, launcher exiting");
            return new LaunchResult(true, 0);
        }

        WinApi.waitForSingleObject(hProcess, WinApi.INFINITE);
        var exitCodeBuf = new byte[4];
        var exitCodeAddr = Address.ofData(exitCodeBuf);
        WinApi.getExitCodeProcess(hProcess, exitCodeAddr);
        var exitCode = exitCodeAddr.getInt();
        Log.info("Java process exited with code: " + exitCode);

        WinApi.closeHandle(hProcess);
        WinApi.closeHandle(hThread);
        return new LaunchResult(true, exitCode);
    }

    /** Spawns cmdLine hidden (no console flash) and waits for it - for internal tool invocations
     *  like tar.exe during auto-install (PRP-09). Unlike launch(), always waits regardless of
     *  console/GUI mode, and never inherits/redirects standard handles. */
    public static boolean runHiddenAndWait(String cmdLine) {
        var si = new byte[STARTUPINFO_SIZE];
        var siAddr = Address.ofData(si);
        siAddr.add(WinOffsets.STARTUPINFOA.cb).putInt(STARTUPINFO_SIZE);

        var pi = new byte[PROCESS_INFO_SIZE];
        var piAddr = Address.ofData(pi);

        var ok = WinApi.createProcessA(Address.fromInt(0), Cstr.of(cmdLine), Address.fromInt(0),
                Address.fromInt(0), 0, WinApi.CREATE_NO_WINDOW, Address.fromInt(0), Address.fromInt(0),
                siAddr, piAddr) != 0;
        if (!ok) {
            return false;
        }

        var hProcess = piAddr.add(WinOffsets.PROCESS_INFORMATION.hProcess).getAddress();
        var hThread = piAddr.add(WinOffsets.PROCESS_INFORMATION.hThread).getAddress();
        WinApi.waitForSingleObject(hProcess, WinApi.INFINITE);
        var exitCodeBuf = new byte[4];
        var exitCodeAddr = Address.ofData(exitCodeBuf);
        WinApi.getExitCodeProcess(hProcess, exitCodeAddr);
        var exitCode = exitCodeAddr.getInt();
        WinApi.closeHandle(hProcess);
        WinApi.closeHandle(hThread);
        return exitCode == 0;
    }
}
