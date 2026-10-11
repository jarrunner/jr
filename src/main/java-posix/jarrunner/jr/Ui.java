package jarrunner.jr;

import java.util.ArrayList;

/** Show a message - POSIX twin of the Windows Ui. Every message prints, and is logged, as on the Windows side. On
 *  macOS an app opened by the system (Finder, the Dock, Launchpad, open) has no terminal to print to, so there it is
 *  also shown in a dialog (PRP-42), through /usr/bin/osascript: part of every macOS, no binding and no C, and the
 *  text goes in as an argument, never into the script, so it needs no escaping. */
public final class Ui {
    private Ui() {}

    /** -Xjr:batch (PRP-42): no dialogs and nothing on stdout; every message goes to stderr, because stdout carries the one JSON line an app reads. */
    public static boolean batch;

    /** macOS, opened by the system: messages also go to a dialog. Set by PosixJr. */
    public static boolean gui;

    /** Launch Services sets __CFBundleIdentifier to the bundle id of the app it opens (measured on macOS 26); a shell
     *  has none, or its terminal's. So: no terminal on stderr, this binary inside an .app, and that variable naming
     *  this .app's own id. Only "inside an .app with stderr redirected" was not enough: a script or a CI runner
     *  running the binary got a dialog nobody could answer, and waited for it. */
    static boolean detectGui() {
        if (Os.LINUX || batch || PosixApi.isatty(2) != 0) return false;
        var exe = ExeInfo.fullPath();
        var at = exe.lastIndexOf(".app/Contents/MacOS/");
        var id = Cstr.readEnv("__CFBundleIdentifier");
        if (at < 0 || id == null || id.isEmpty()) return false;
        var plist = FileIo.readAll(exe.substring(0, at + 4) + "/Contents/Info.plist");
        return plist != null && plist.contains(">" + id + "<");
    }

    public static void info(boolean hasConsole, String title, String message) {
        show(title, message, false);
    }

    public static void error(boolean hasConsole, String title, String message) {
        show(title, message, true);
    }

    private static void show(String title, String message, boolean isError) {
        var text = "\n" + (isError ? "[ERROR] " : "[INFO] ") + title + "\n" + message + "\n\n";
        if (isError || batch) {
            Stderr.print(text);
        } else {
            Stderr.out(text);
        }
        Log.write(isError ? "ERROR" : "INFO", title + ": " + message);
        if (gui) dialog(title, message, "{\"OK\"}", "\"OK\"", isError ? "stop" : "note");
    }

    /** Asks yes or no: a dialog when opened by the system, else "[Y/n]" on a terminal. With neither (a script, a
     *  pipe) it does not guess: no, with -Xjr:yes named as the way to say yes. */
    static boolean confirm(String title, String question, String yes) {
        if (gui) return dialog(title, question, "{\"Cancel\", \"" + yes + "\"}", "\"" + yes + "\"", "note");
        if (batch || PosixApi.isatty(0) == 0) {
            Stderr.print("\n" + title + "\n" + question + "\nNo terminal to ask on, so no. Run it again with -Xjr:yes to say yes.\n\n");
            return false;
        }
        Stderr.print("\n" + title + "\n" + question + " [Y/n] ");
        var tty = PosixApi.fopen("/dev/tty", "r");
        if (tty.toLong() == 0) return false;
        var c = Buf.alloc(1);
        var answer = PosixApi.fread(c.ptr(), 1, 1, tty) == 1 ? (char) (c.getByte(0) & 0xFF) : 'n';
        PosixApi.fclose(tty);
        return answer == '\n' || answer == 'y' || answer == 'Y';
    }

    /** A notification in the corner of the screen, when opened by the system (a long download, so the app does not seem stuck). */
    static void notify(String title, String message) {
        Log.info(title + ": " + message);
        if (gui) osascript("display notification (item 1 of argv) with title (item 2 of argv)", message, title);
    }

    /** True when a button other than Cancel was pressed (osascript exits 1 on Cancel). */
    private static boolean dialog(String title, String message, String buttons, String defaultButton, String icon) {
        return osascript("display dialog (item 1 of argv) with title (item 2 of argv) buttons " + buttons
                + " default button " + defaultButton + " with icon " + icon, message, title) == 0;
    }

    private static int osascript(String statement, String message, String title) {
        var args = new ArrayList<String>();
        args.add("-e");
        args.add("on run argv");
        args.add("-e");
        args.add(statement);
        args.add("-e");
        args.add("return \"\"");
        args.add("-e");
        args.add("end run");
        args.add(message);
        args.add(title);
        var r = ProcessLauncher.launch("/usr/bin/osascript", args);
        return r.started ? r.exitCode : -1;
    }
}
