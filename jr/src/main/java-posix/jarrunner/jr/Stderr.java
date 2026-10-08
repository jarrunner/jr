package jarrunner.jr;

import static jarrunner.jr.N.*;

/** stderr and stdout as UTF-8 bytes, POSIX twin of the Windows Stderr. TeaVM's System.out/System.err print through
 *  putwchar, which follows the C locale: under "C" (no LANG, as for a macOS app opened from Finder) anything
 *  non-ASCII is lost, and System.err lands on stdout anyway (PRP-30, PRP-34). */
public final class Stderr {
    private Stderr() {}

    public static void print(String s) {
        write(2, s);
    }

    public static void println(String s) {
        print(s + "\n");
    }

    /** stdout, for the answers jr prints (help, "Wrote ..."). */
    public static void out(String s) {
        write(1, s);
    }

    @Unsafe("trusts N.utf8's allocation: at most 3 bytes per char plus the NUL")
    private static void write(int fd, String s) {
        if (s == null || s.isEmpty()) return;
        memScoped(() -> {
            var bytes = Buf.wrap(utf8(s), s.length() * 3 + 1);
            var n = 0;
            while (bytes.getByte(n) != 0) n++;
            var done = 0;
            while (done < n) {
                var w = PosixApi.write(fd, bytes.slice(done, n - done).ptr(), n - done);
                if (w <= 0) break;
                done += (int) w;
            }
        });
    }
}
