package jarrunner.jr;

import static jarrunner.jr.N.*;

/** Show a message the same way launcher.c's showMessage does: printf in console mode, a real
 *  MessageBoxA in GUI mode - plus always logging it. */
public final class Ui {
    private Ui() {}

    /** -Xjr:batch (PRP-42): no dialogs and nothing on stdout; every message goes to stderr, because stdout carries the one JSON line an app reads. */
    public static boolean batch;

    public static void info(boolean hasConsole, String title, String message) {
        show(hasConsole, title, message, WinApi.MB_ICONINFORMATION);
    }

    public static void error(boolean hasConsole, String title, String message) {
        show(hasConsole, title, message, WinApi.MB_ICONERROR);
    }

    private static void show(boolean hasConsole, String title, String message, int type) {
        if (batch) {
            Stderr.print((type == WinApi.MB_ICONERROR ? "[ERROR] " : "[INFO] ") + title + ": " + message + "\n");
            Log.write(type == WinApi.MB_ICONERROR ? "ERROR" : "INFO", title + ": " + message);
            return;
        }
        if (type == WinApi.MB_ICONERROR) {
            // PRP-31: every error becomes a saved, redacted report; GUI mode shows it in ErrorDialog.
            var report = ErrorReport.build(title, message);
            var saved = ErrorReport.save(report);
            if (hasConsole) {
                Stderr.print("\n[ERROR] " + title + "\n" + message + "\n" + (saved.isEmpty() ? "" : "Report: " + saved + "\n") + "\n");
            } else if (ErrorReport.tooMany()) {
                Log.warn("Error dialog not shown (shown too often in the last minutes); report: " + saved);
            } else {
                var blank = message.indexOf("\n\n");
                ErrorDialog.show(title, title + "\n" + (blank > 0 ? message.substring(0, blank) : message), report, saved);
            }
        } else if (hasConsole) {
            Stderr.out("\n[INFO] " + title + "\n" + message + "\n\n");
        } else {
            WinApi.messageBoxW(NULL, message, title, type);
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
            wcstrInto(p, s);
            WinApi.globalUnlock(h);
            WinApi.setClipboardData(WinApi.CF_UNICODETEXT, h);
        }
        WinApi.closeClipboard();
    }
}
