package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** PRP-31: what the JVM printed when a launch failed. A GUI-mode exe has no console, so its stderr goes to
 *  %USERPROFILE%\.jr\runs\<exe>.stderr.txt (overwritten each run) instead of nowhere. A console-mode exe
 *  leaves stderr alone, so the user still sees everything live, and the lines the launch added to the
 *  console are read back afterwards. */
public final class StartCapture {
    private StartCapture() {}

    private static final int MAX_TEXT = 16 * 1024, MAX_ROWS = 40;

    static String file = "";
    static long startTick;
    private static int consoleRow = -1;

    /** Before a launch. Returns the inheritable stderr file handle in GUI mode, NULL in console mode. */
    @Acquires("closeHandle")
    static Address begin(boolean hasConsole) {
        startTick = WinApi.getTickCount64();
        if (hasConsole) {
            consoleRow = memScoped(() -> {
                var info = alloc(WinOffsets.CONSOLE_SCREEN_BUFFER_INFO.SIZE);
                var con = console();
                var ok = con != WinApi.INVALID_HANDLE_VALUE && WinApi.getConsoleScreenBufferInfo(con, info) != 0;
                WinApi.closeHandle(con);
                return ok ? WinOffsets.COORD.Y(WinOffsets.CONSOLE_SCREEN_BUFFER_INFO.dwCursorPosition(info)) : -1;
            });
            return NULL;
        }
        var dir = JrDirs.of("runs");
        if (dir == null) {
            return NULL;
        }
        // One file per run, so two copies of the app never write into each other's; older ones are removed
        // (one still open by a running copy simply stays).
        var base = ExeInfo.baseNameNoExt();
        for (var old : Dirs.matching(dir, base + "-*.stderr.txt")) {
            WinApi.deleteFileW(dir + "\\" + old);
        }
        file = dir + "\\" + base + "-" + startTick + ".stderr.txt";
        var h = appendHandle();
        if (h == WinApi.INVALID_HANDLE_VALUE) {
            file = "";
            return NULL;
        }
        WinApi.setHandleInformation(h, WinApi.HANDLE_FLAG_INHERIT, WinApi.HANDLE_FLAG_INHERIT);
        return h;
    }

    static long elapsedMillis() {
        return WinApi.getTickCount64() - startTick;
    }

    /** After a launch: the captured text, at most 16 KB, "" if there is none. */
    static String end(boolean hasConsole) {
        if (!hasConsole) {
            var text = file.isEmpty() ? null : FileIo.readAll(file);
            return text == null ? "" : text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        }
        return consoleRow < 0 ? "" : memScoped(() -> consoleLines());
    }

    private static String consoleLines() {
        var con = console();
        var info = alloc(WinOffsets.CONSOLE_SCREEN_BUFFER_INFO.SIZE);
        if (con == WinApi.INVALID_HANDLE_VALUE) {
            return "";
        }
        if (WinApi.getConsoleScreenBufferInfo(con, info) == 0) {
            WinApi.closeHandle(con);
            return "";
        }
        var width = WinOffsets.COORD.X(WinOffsets.CONSOLE_SCREEN_BUFFER_INFO.dwSize(info));
        var last = WinOffsets.COORD.Y(WinOffsets.CONSOLE_SCREEN_BUFFER_INFO.dwCursorPosition(info));
        var first = Math.max(Math.max(consoleRow, 0), last - MAX_ROWS);
        var buf = alloc(width * 2 + 2);
        var coord = alloc(WinOffsets.COORD.SIZE);
        var read = intVar();
        var sb = new StringBuilder();
        for (var row = first; row <= last; row++) {
            WinOffsets.COORD.Y(coord, (short) row);
            if (WinApi.readConsoleOutputCharacterW(con, buf, width, coord, read) == 0) {
                break;
            }
            var line = wstring(buf, intOf(read)).stripTrailing();
            if (!line.isEmpty()) {
                sb.append(line).append('\n');
            }
        }
        WinApi.closeHandle(con);
        return sb.toString();
    }

    @Acquires("closeHandle")
    @Fails("INVALID_HANDLE_VALUE")
    private static Address console() {
        return WinApi.createFileW("CONOUT$", WinApi.GENERIC_READ | WinApi.GENERIC_WRITE, WinApi.FILE_SHARE_READ | WinApi.FILE_SHARE_WRITE, NULL, WinApi.OPEN_EXISTING, 0, NULL);
    }

    /** Append-only, so jr, msvcrt and the JDK's own C runtime can all write to the file without overwriting each other. */
    @Acquires("closeHandle")
    @Fails("INVALID_HANDLE_VALUE")
    private static Address appendHandle() {
        return WinApi.createFileW(file, WinApi.FILE_APPEND_DATA, WinApi.FILE_SHARE_READ | WinApi.FILE_SHARE_WRITE, NULL, WinApi.OPEN_ALWAYS, WinApi.FILE_ATTRIBUTE_NORMAL, NULL);
    }

    /** In-process GUI mode: points the Universal CRT's fd 2 (the JDK's stderr) at the capture file too. */
    @Unsafe("trusts that the CRT's _open_osfhandle and _dup2 have the signature CrtFdFn declares")
    static void bindJdkStderr() {
        if (file.isEmpty()) {
            return;
        }
        var ucrt = WinApi.loadLibraryW("ucrtbase.dll");
        if (ucrt.toLong() == 0) {
            return;
        }
        handOver(ucrt); // stays loaded: the in-process JDK uses it for the life of the process
        var open = WinApi.getProcAddress(ucrt, "_open_osfhandle");
        var dup2 = WinApi.getProcAddress(ucrt, "_dup2");
        if (open.toLong() == 0 || dup2.toLong() == 0) {
            return;
        }
        var h = appendHandle();
        if (h == WinApi.INVALID_HANDLE_VALUE) {
            return;
        }
        var fd = ((CrtFdFn) (Object) open).invoke(h.toLong(), 1); // _O_WRONLY
        if (fd < 0) {
            WinApi.closeHandle(h);
            return;
        }
        handOver(h); // the CRT's fd now owns it, and fd 2 keeps it open for the JDK
        ((CrtFdFn) (Object) dup2).invoke(fd, 2);
    }
}
