package jarrunner.jr;

/** Show a message - POSIX twin of the Windows Ui. There is no MessageBoxA equivalent in scope
 *  here (no GUI subsystem concept on this build, see ConsoleMode), so every message prints,
 *  always - plus always logging it, same as the Windows side. */
public final class Ui {
    private Ui() {}

    public static void info(boolean hasConsole, String title, String message) {
        show(title, message, false);
    }

    public static void error(boolean hasConsole, String title, String message) {
        show(title, message, true);
    }

    private static void show(String title, String message, boolean isError) {
        var text = "\n" + (isError ? "[ERROR] " : "[INFO] ") + title + "\n" + message + "\n\n";
        if (isError) {
            Stderr.print(text);
        } else {
            Stderr.out(text);
        }
        Log.write(isError ? "ERROR" : "INFO", title + ": " + message);
    }
}
