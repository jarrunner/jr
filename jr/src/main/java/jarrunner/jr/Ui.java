package jarrunner.jr;

import static jarrunner.jr.N.*;

/** Show a message the same way launcher.c's showMessage does: printf in console mode, a real
 *  MessageBoxA in GUI mode - plus always logging it. */
public final class Ui {
    private Ui() {}

    public static void info(boolean hasConsole, String title, String message) {
        show(hasConsole, title, message, WinApi.MB_ICONINFORMATION);
    }

    public static void error(boolean hasConsole, String title, String message) {
        show(hasConsole, title, message, WinApi.MB_ICONERROR);
    }

    private static void show(boolean hasConsole, String title, String message, int type) {
        if (hasConsole) {
            // An error goes to the real stderr (see Stderr), information to stdout.
            var text = "\n" + (type == WinApi.MB_ICONERROR ? "[ERROR] " : "[INFO] ") + title + "\n" + message + "\n\n";
            if (type == WinApi.MB_ICONERROR) {
                Stderr.print(text);
            } else {
                System.out.print(text);
            }
        } else if (type == WinApi.MB_ICONERROR) {
            // People have to report these, so the whole text is one click away: OK copies it.
            // OK/Cancel rather than Yes/No, because only then do Esc and the close button work.
            var text = message + "\n\n" + ExeInfo.fullPath();
            if (WinApi.messageBoxA(NULL, cstr(text + "\n\nOK copies this message, Esc closes."), cstr(title),
                    type | WinApi.MB_OKCANCEL) == WinApi.IDOK) {
                copy(title + "\n" + text);
            }
        } else {
            WinApi.messageBoxA(NULL, cstr(message), cstr(title), type);
        }
        Log.write(type == WinApi.MB_ICONERROR ? "ERROR" : "INFO", title + ": " + message);
    }

    /** Puts text on the clipboard as UTF-16, the one format every app pastes. */
    static void copy(String s) {
        if (WinApi.openClipboard(NULL) == 0) return;
        WinApi.emptyClipboard();
        var n = s.length();
        var h = WinApi.globalAlloc(WinApi.GMEM_MOVEABLE, (n + 1) * 2L);
        var p = h.toLong() == 0 ? NULL : WinApi.globalLock(h);
        if (p.toLong() != 0) {
            for (var i = 0; i < n; i++) p.add(i * 2).putChar(s.charAt(i));
            p.add(n * 2).putChar((char) 0);
            WinApi.globalUnlock(h);
            WinApi.setClipboardData(WinApi.CF_UNICODETEXT, h);
        }
        WinApi.closeClipboard();
    }
}
