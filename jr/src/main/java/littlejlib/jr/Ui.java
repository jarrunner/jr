package littlejlib.jr;

import static littlejlib.jr.N.*;

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
            var prefix = type == WinApi.MB_ICONERROR ? "[ERROR] " : "[INFO] ";
            System.out.println();
            System.out.println(prefix + title);
            System.out.println(message);
            System.out.println();
        } else {
            WinApi.messageBoxA(NULL, cstr(message), cstr(title), type);
        }
        Log.write(type == WinApi.MB_ICONERROR ? "ERROR" : "INFO", title + ": " + message);
    }
}
