import java.lang.foreign.*;
import java.nio.charset.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.*;
import static widestr.widestr_h.*;

/** Reads back every string macro the fork generated and compares its bytes with the C literal's own encoding. */
public class WideTest {
    static int failures;

    public static void main(String[] a) {
        check("NARROW", NARROW(), "plain", UTF_8);
        check("W_SIMPLE", W_SIMPLE(), "SHA256", UTF_16LE);
        check("W_CHAIN", W_CHAIN(), "SHA256", UTF_16LE);
        check("W_CONCAT", W_CONCAT(), "HashDigestLength", UTF_16LE);
        check("W_NONASCII", W_NONASCII(), "caf\u00e9 \u03a9", UTF_16LE);
        check("W_ASTRAL", W_ASTRAL(), "\ud83d\ude00!", UTF_16LE);
        check("W_EMBEDDED_NUL", W_EMBEDDED_NUL(), "a\u0000b", UTF_16LE);
        check("U16", U16(), "utf16", UTF_16LE);
        check("U32", U32(), "\ud83d\ude00", Charset.forName("UTF-32LE"));
        System.out.println(failures == 0 ? "ALL MATCH" : failures + " FAILED");
        System.exit(failures);
    }

    static void check(String name, MemorySegment seg, String expected, Charset cs) {
        var want = (expected + "\u0000").getBytes(cs);
        var got = seg.toArray(ValueLayout.JAVA_BYTE);
        var ok = Arrays.equals(want, got);
        if (!ok) failures++;
        System.out.printf("%-15s %s  %d bytes%n", name, ok ? "ok  " : "FAIL", got.length);
    }
}
