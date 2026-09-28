package littlejlib.jr;

import static littlejlib.jr.N.*;

/** Environment lookup. String conversion itself lives in {@link N} (cstr, wcstr, string). */
public final class Cstr {
    private Cstr() {}

    public static String readEnv(String name) {
        return string(WinApi.getenv(cstr(name)));
    }
}
