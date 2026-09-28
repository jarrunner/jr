package pocapp.jr;

import org.teavm.interop.Address;

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

    private static String resolveRealPath(String path) {
        var h = WinApi.createFileA(Cstr.of(path), 0,
                WinApi.FILE_SHARE_READ | WinApi.FILE_SHARE_WRITE | WinApi.FILE_SHARE_DELETE,
                Address.fromInt(0), WinApi.OPEN_EXISTING, WinApi.FILE_FLAG_BACKUP_SEMANTICS, Address.fromInt(0));
        if (h == WinApi.INVALID_HANDLE_VALUE) {
            return null;
        }
        var buf = new byte[1024];
        var addr = Address.ofData(buf);
        var len = WinApi.getFinalPathNameByHandleA(h, addr, buf.length, 0);
        WinApi.closeHandle(h);
        if (len == 0 || len >= buf.length) {
            return null;
        }
        var sb = new StringBuilder();
        for (var i = 0; i < len; i++) {
            sb.append((char) (buf[i] & 0xFF));
        }
        var s = sb.toString();
        return s.startsWith("\\\\?\\") ? s.substring(4) : s;
    }
}
