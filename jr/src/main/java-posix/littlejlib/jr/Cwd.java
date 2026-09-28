package littlejlib.jr;

import static littlejlib.jr.N.*;

/** getcwd(3) wrapper. */
public final class Cwd {
    private Cwd() {}

    public static String get() {
        var buf = alloc(PosixApi.PATH_MAX);
        var p = PosixApi.getcwd(buf, PosixApi.PATH_MAX);
        return p.toLong() == 0 ? "" : string(buf);
    }
}
