package littlejlib.jr;

/** Windows' console-vs-GUI detection (FreeConsole/AttachConsole) has no POSIX equivalent - a
 *  native binary launched from a terminal always has one; jr's own concept of "GUI mode" (double-
 *  clicked, no console) does not map onto Linux/macOS CLI usage the same way, so this build always
 *  behaves as jr's console mode. isGuiMode() checks isatty(1) purely for the help text's
 *  "Execution Context" line, not to change behaviour - out of scope per CLAUDE.md's platform
 *  decision (see PRP-21). */
public final class ConsoleMode {
    private ConsoleMode() {}

    public static boolean isGuiMode() {
        return PosixApi.isatty(1) == 0;
    }

    public static void restoreRedirectedStdHandles() {}

    public static void rebindConsoleStdStreams(boolean hasConsole) {}
}
