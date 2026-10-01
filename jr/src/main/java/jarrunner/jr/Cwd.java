package jarrunner.jr;

import static jarrunner.jr.N.*;

/** GetCurrentDirectoryA wrapper. */
public final class Cwd {
    private Cwd() {}

    public static String get() {
        var buf = alloc(1024);
        var len = WinApi.getCurrentDirectoryA(1024, buf);
        return len <= 0 ? "" : string(buf, len);
    }
}
