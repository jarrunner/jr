package littlejlib.jr;

import static littlejlib.jr.N.*;

/** Finding java.exe/javaw.exe (PATH or --java-home) and jli.dll next to it - mirrors launcher.c. */
public final class JavaFinder {
    private JavaFinder() {}

    public static String findInPath(String exeName) {
        var path = Cstr.readEnv("PATH");
        if (path == null) {
            return null;
        }
        var start = 0;
        while (start <= path.length()) {
            var sep = path.indexOf(';', start);
            var dir = sep < 0 ? path.substring(start) : path.substring(start, sep);
            if (!dir.isEmpty()) {
                var candidate = dir + "\\" + exeName;
                if (FileIo.exists(candidate)) {
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

    /** Right next to java.exe, or via a resolved symlink (the Oracle javapath shim), or JAVA_HOME. */
    public static String findJliDll(String javaExePath) {
        var dir = Paths.dirOf(javaExePath);
        if (!dir.isEmpty()) {
            var candidate = dir + "\\jli.dll";
            if (FileIo.exists(candidate)) {
                return candidate;
            }
        }

        var realPath = resolveRealPath(javaExePath);
        if (realPath != null) {
            dir = Paths.dirOf(realPath);
            if (!dir.isEmpty()) {
                var candidate = dir + "\\jli.dll";
                if (FileIo.exists(candidate)) {
                    return candidate;
                }
            }
        }

        var javaHome = Cstr.readEnv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            var candidate = javaHome + "\\bin\\jli.dll";
            if (FileIo.exists(candidate)) {
                return candidate;
            }
            candidate = javaHome + "\\jre\\bin\\jli.dll";
            if (FileIo.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Major version of a java.exe/javaw.exe, read from &lt;home&gt;\release rather than by spawning
     *  "java -version" (which would cost a whole JVM start on every launch). Follows a symlink,
     *  e.g. the Oracle javapath shim, when the direct parent has no release file. Returns 0 if it
     *  cannot be determined - mirrors launcher.c's detectJavaMajor. */
    public static int detectMajorVersion(String javaExePath) {
        var home = Paths.dirOf(Paths.dirOf(javaExePath));
        var major = home.isEmpty() ? 0 : readReleaseMajor(home);

        if (major == 0) {
            var realPath = resolveRealPath(javaExePath);
            if (realPath != null) {
                home = Paths.dirOf(Paths.dirOf(realPath));
                if (!home.isEmpty()) {
                    major = readReleaseMajor(home);
                }
            }
        }
        return major;
    }

    /** JAVA_VERSION="25.0.1" -&gt; 25, JAVA_VERSION="1.8.0_402" -&gt; 8 (legacy 1.x numbering).
     *  Returns 0 if there is no readable release file - mirrors launcher.c's readReleaseMajor. */
    private static int readReleaseMajor(String jdkHome) {
        var text = FileIo.readAll(jdkHome + "\\release");
        if (text == null) {
            return 0;
        }
        for (var line : Lines.split(text)) {
            if (line.startsWith("JAVA_VERSION=")) {
                var v = line.substring("JAVA_VERSION=".length());
                if (v.startsWith("\"")) {
                    v = v.substring(1);
                }
                var major = Atoi.parse(v);
                if (major == 1) { // legacy 1.x numbering
                    var dot = v.indexOf('.');
                    major = dot >= 0 ? Atoi.parse(v.substring(dot + 1)) : 0;
                }
                return major;
            }
        }
        return 0;
    }

    private static String resolveRealPath(String path) {
        var h = WinApi.createFileA(cstr(path), 0,
                WinApi.FILE_SHARE_READ | WinApi.FILE_SHARE_WRITE | WinApi.FILE_SHARE_DELETE,
                NULL, WinApi.OPEN_EXISTING, WinApi.FILE_FLAG_BACKUP_SEMANTICS, NULL);
        if (h == WinApi.INVALID_HANDLE_VALUE) {
            return null;
        }
        var buf = alloc(1024);
        var len = WinApi.getFinalPathNameByHandleA(h, buf, 1024, 0);
        WinApi.closeHandle(h);
        if (len == 0 || len >= 1024) {
            return null;
        }
        var s = string(buf, len);
        return s.startsWith("\\\\?\\") ? s.substring(4) : s;
    }
}
