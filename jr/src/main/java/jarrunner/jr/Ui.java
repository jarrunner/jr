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
        } else {
            WinApi.messageBoxA(NULL, cstr(message), cstr(title), type);
        }
        Log.write(type == WinApi.MB_ICONERROR ? "ERROR" : "INFO", title + ": " + message);
    }
}
