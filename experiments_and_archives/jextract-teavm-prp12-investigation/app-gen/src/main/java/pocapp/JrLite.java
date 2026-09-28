package pocapp;

import org.teavm.interop.Address;
import org.teavm.interop.Import;

/**
 * A "jr-lite" equivalent of launcher.c's core job, written in Java and compiled
 * through TeaVM's C backend + llvm-mingw (msvcrt), for PRP 07's size/functionality
 * comparison. Deliberately NOT a full port - see 07-prp.02.step1-findings.md for
 * what launcher.c actually does. This covers:
 *   - console-vs-GUI detection (the FreeConsole/AttachConsole dance)
 *   - finding java.exe/javaw.exe on PATH, or via --java-home
 *   - building the java invocation and running it, faithfully returning its exit
 *     code in console mode, or launching detached in GUI mode
 * Deliberately NOT included in this pass: the .jrc config file, AOT cache
 * management, and the jvm-dll in-process JLI mode - each is a real separate
 * feature layered on much later in launcher.c's own history (PRP-02/03/05).
 */
public class JrLite {
    @Import(name = "exit")
    private static native void cExit(int code);

    @Import(name = "system")
    private static native int cSystem(Address cmd);

    @Import(name = "WinExec")
    private static native int cWinExec(Address cmd, int cmdShow);

    @Import(name = "GetFileAttributesA")
    private static native int cGetFileAttributesA(Address path);

    @Import(name = "GetConsoleWindow")
    private static native Address cGetConsoleWindow();

    @Import(name = "ShowWindow")
    private static native int cShowWindow(Address hwnd, int cmdShow);

    @Import(name = "FreeConsole")
    private static native int cFreeConsole();

    @Import(name = "AttachConsole")
    private static native int cAttachConsole(int processId);

    private static final int INVALID_FILE_ATTRIBUTES = -1;
    private static final int SW_HIDE = 0;
    private static final int SW_SHOWNORMAL = 1;
    private static final int SW_SHOW = 5;
    private static final int ATTACH_PARENT_PROCESS = -1;

    private static Address cstr(String s) {
        byte[] b = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        b[s.length()] = 0;
        return Address.ofData(b);
    }

    private static boolean fileExists(String path) {
        return cGetFileAttributesA(cstr(path)) != INVALID_FILE_ATTRIBUTES;
    }

    private static boolean isGuiMode() {
        Address consoleWnd = cGetConsoleWindow();
        if (consoleWnd.toLong() != 0) {
            cShowWindow(consoleWnd, SW_HIDE);
        }
        cFreeConsole();
        boolean attached = cAttachConsole(ATTACH_PARENT_PROCESS) != 0;
        if (attached) {
            consoleWnd = cGetConsoleWindow();
            if (consoleWnd.toLong() != 0) {
                cShowWindow(consoleWnd, SW_SHOW);
            }
            return false;
        }
        return true;
    }

    private static String findJavaInPath(String exeName) {
        String path = getEnv("PATH");
        if (path == null) {
            return null;
        }
        int start = 0;
        while (start <= path.length()) {
            int sep = path.indexOf(';', start);
            String dir = sep < 0 ? path.substring(start) : path.substring(start, sep);
            if (!dir.isEmpty()) {
                String candidate = dir + "\\" + exeName;
                if (fileExists(candidate)) {
                    return candidate;
                }
            }
            if (sep < 0) {
                break;
            }
            start = sep + 1;
        }
        return null;
    }

    @Import(name = "getenv")
    private static native Address cGetEnv(Address name);

    private static String getEnv(String name) {
        Address p = cGetEnv(cstr(name));
        if (p.toLong() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            byte b = p.add(i).getByte();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
            i++;
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        boolean guiMode = isGuiMode();
        boolean hasConsole = !guiMode;
        String javaExeName = hasConsole ? "java.exe" : "javaw.exe";

        String javaHome = null;
        String[] rest = new String[args.length];
        int restCount = 0;
        for (String a : args) {
            if (a.startsWith("--java-home=")) {
                javaHome = a.substring("--java-home=".length());
            } else {
                rest[restCount++] = a;
            }
        }

        String javaPath;
        if (javaHome != null) {
            javaPath = javaHome + "\\bin\\" + javaExeName;
            if (!fileExists(javaPath)) {
                System.out.println("Java not found at: " + javaPath);
                cExit(1);
                return;
            }
        } else {
            javaPath = findJavaInPath(javaExeName);
            if (javaPath == null) {
                System.out.println("Java not found in PATH (looking for " + javaExeName + ")");
                cExit(1);
                return;
            }
        }

        if (restCount == 0) {
            System.out.println("jr-lite (TeaVM PoC) - usage: <jar-file> [args...]");
            System.out.println("Java: " + javaPath);
            cExit(1);
            return;
        }

        StringBuilder cmd = new StringBuilder();
        cmd.append('"').append(javaPath).append('"').append(" -jar ");
        for (int i = 0; i < restCount; i++) {
            if (i > 0) {
                cmd.append(' ');
            }
            cmd.append(rest[i]);
        }

        if (hasConsole) {
            int exitCode = cSystem(cstr(cmd.toString()));
            cExit(exitCode);
        } else {
            cWinExec(cstr(cmd.toString()), SW_SHOWNORMAL);
            cExit(0);
        }
    }
}
