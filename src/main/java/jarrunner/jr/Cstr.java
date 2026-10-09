package jarrunner.jr;

import static jarrunner.jr.N.*;

/** The process environment, through GetEnvironmentVariableW/SetEnvironmentVariableW (PRP-34: the CRT's getenv
 *  is ANSI). String conversion itself lives in {@link N}. */
public final class Cstr {
    private Cstr() {}

    /** The variable's value, or null if it is not set. */
    public static String readEnv(String name) {
        return memScoped(() -> {
            var w = wcstr(name);
            var n = WinApi.getEnvironmentVariableW(w, NULL, 0);
            if (n == 0) return null;
            var buf = alloc(n * 2 + 2);
            var got = WinApi.getEnvironmentVariableW(w, buf, n);
            return got >= n ? null : wstring(buf, got);
        });
    }

    public static void setEnv(String name, String value) {
        memScoped(() -> { WinApi.setEnvironmentVariableW(name, value); });
    }
}
