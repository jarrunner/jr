package jarrunner.jr;

import static jarrunner.jr.N.*;

/** GetCurrentDirectoryW wrapper. */
public final class Cwd {
    private Cwd() {}

    public static String get() {
        return memScoped(() -> {
            var n = WinApi.getCurrentDirectoryW(0, NULL);
            var buf = alloc(n * 2 + 2);
            var len = WinApi.getCurrentDirectoryW(n, buf);
            return len <= 0 || len >= n ? "" : wstring(buf, len);
        });
    }
}
