package jarrunner.jr;

import static jarrunner.jr.N.*;

/** java.exe on PATH (for -Xjr:help), and resolving a symlinked java.exe to its real path (JavaSources). */
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

    static String resolveRealPath(String path) {
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
