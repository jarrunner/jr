package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/**
 * Console-vs-GUI detection (the FreeConsole/AttachConsole dance) and the std-handle save/restore
 * that undoes AttachConsole's side effect of discarding any redirection the user asked for -
 * mirrors launcher.c's isGuiMode/saveStdHandles/restoreRedirectedStdHandles/rebindConsoleStdStreams.
 */
public final class ConsoleMode {
    private ConsoleMode() {}

    // Address values must NOT be stored in a regular Object[] array on TeaVM's C backend -
    // Address is a raw-pointer intrinsic, not a real heap object, and an Address[] silently
    // corrupts (confirmed: segfaults inside the very save/restore loop that used one - see
    // guidelines.teavmcpp.md). Three plain fields instead of a loop over an array.
    private static Address saved0, saved1, saved2;
    private static boolean wasConsole0, wasConsole1, wasConsole2;

    public static boolean isGuiMode() {
        saveStdHandles();

        var consoleWnd = WinApi.getConsoleWindow();
        if (consoleWnd.toLong() != 0) {
            WinApi.showWindow(consoleWnd, WinApi.SW_HIDE);
        }

        WinApi.freeConsole();
        var attached = WinApi.attachConsole(WinApi.ATTACH_PARENT_PROCESS) != 0;

        if (attached) {
            consoleWnd = WinApi.getConsoleWindow();
            if (consoleWnd.toLong() != 0) {
                WinApi.showWindow(consoleWnd, WinApi.SW_SHOW);
            }
            return false;
        }
        return true;
    }

    private static void saveStdHandles() {
        var mode = intVar();

        saved0 = WinApi.getStdHandle(WinApi.STD_INPUT_HANDLE);
        wasConsole0 = isConsoleHandle(saved0, mode);

        saved1 = WinApi.getStdHandle(WinApi.STD_OUTPUT_HANDLE);
        wasConsole1 = isConsoleHandle(saved1, mode);

        saved2 = WinApi.getStdHandle(WinApi.STD_ERROR_HANDLE);
        wasConsole2 = isConsoleHandle(saved2, mode);
    }

    private static boolean isConsoleHandle(Address h, Address mode) {
        return h.toLong() != 0 && h != WinApi.INVALID_HANDLE_VALUE
                && WinApi.getConsoleMode(h, mode) != 0;
    }

    public static void restoreRedirectedStdHandles() {
        restoreOne(WinApi.STD_INPUT_HANDLE, wasConsole0, saved0);
        restoreOne(WinApi.STD_OUTPUT_HANDLE, wasConsole1, saved1);
        restoreOne(WinApi.STD_ERROR_HANDLE, wasConsole2, saved2);
    }

    private static void restoreOne(int stdId, boolean wasConsole, Address saved) {
        if (!wasConsole && saved != null && saved.toLong() != 0 && saved != WinApi.INVALID_HANDLE_VALUE) {
            WinApi.setStdHandle(stdId, saved);
        }
    }

    /** Only needed for jvm-dll in-process mode: an in-process JVM inherits our fd table rather
     *  than being handed fresh handles, so the console ones (not the redirected ones, which are
     *  already correct) need repointing at whichever console we ended up attached to. */
    public static void rebindConsoleStdStreams(boolean hasConsole) {
        rebindOne(0, WinApi.STD_INPUT_HANDLE, wasConsole0, hasConsole);
        rebindOne(1, WinApi.STD_OUTPUT_HANDLE, wasConsole1, hasConsole);
        rebindOne(2, WinApi.STD_ERROR_HANDLE, wasConsole2, hasConsole);
    }

    private static void rebindOne(int fd, int stdId, boolean wasConsole, boolean hasConsole) {
        if (!wasConsole) {
            return;
        }
        Address h;
        if (hasConsole) {
            h = WinApi.createFileA(cstr(fd == 0 ? "CONIN$" : "CONOUT$"),
                    WinApi.GENERIC_READ | WinApi.GENERIC_WRITE,
                    WinApi.FILE_SHARE_READ | WinApi.FILE_SHARE_WRITE,
                    NULL, WinApi.OPEN_EXISTING, 0, NULL);
        } else {
            h = WinApi.createFileA(cstr("NUL"),
                    WinApi.GENERIC_READ | WinApi.GENERIC_WRITE,
                    WinApi.FILE_SHARE_READ | WinApi.FILE_SHARE_WRITE,
                    NULL, WinApi.OPEN_EXISTING, 0, NULL);
        }
        if (h != WinApi.INVALID_HANDLE_VALUE) {
            bindStdStream(fd, stdId, h);
        }
    }

    private static void bindStdStream(int fd, int stdId, Address h) {
        var flags = fd == 0 ? 0x0000 : 0x0001; // _O_RDONLY : _O_WRONLY
        var tmp = WinApi.openOsfHandle(h.toLong(), flags);
        if (tmp < 0) {
            WinApi.closeHandle(h);
            return;
        }
        if (WinApi.dup2(tmp, fd) == 0) {
            WinApi.close(tmp);
            WinApi.setStdHandle(stdId, Address.fromLong(WinApi.getOsfHandle(fd)));
        } else {
            WinApi.close(tmp);
        }
    }
}
