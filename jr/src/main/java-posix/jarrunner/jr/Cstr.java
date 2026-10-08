package jarrunner.jr;

import static jarrunner.jr.N.*;

/** Environment lookup. String conversion itself lives in {@link N} (cstr, string). */
public final class Cstr {
    private Cstr() {}

    public static String readEnv(String name) {
        return string(PosixApi.getenv(utf8(name)));
    }
}
