package pocapp.jr;

import java.util.function.Supplier;
import org.teavm.interop.Address;

/** Native memory for WinApi calls, used as {@code import static pocapp.jr.N.*;}. Names follow Kotlin/Native and C.
 *  Everything is allocated off the TeaVM heap (see {@link Arena}), so no GC can free or move it under a pointer, and
 *  allocating Java objects between taking a pointer and the call that uses it is harmless. Memory lives until the
 *  innermost {@link #memScoped} ends, or for the whole program outside any scope. Never store a pointer from here
 *  in a field, and never use one after its scope. Never pass {@code Address.ofData} of a Java array to native code;
 *  use {@link #alloc} instead (prp/18-prp.01 has the measurements behind these rules). */
public final class N {
    private N() {}

    public static final Address NULL = Address.fromInt(0);

    public static <T> T memScoped(Supplier<T> body) {
        var mark = Arena.mark();
        try { return body.get(); } finally { Arena.reset(mark); }
    }

    public static void memScoped(Runnable body) {
        var mark = Arena.mark();
        try { body.run(); } finally { Arena.reset(mark); }
    }

    /** NUL-terminated byte string, one byte per char (the ANSI "A" APIs; jr's strings are ASCII). */
    public static Address cstr(String s) {
        if (s == null) s = "";
        var n = s.length();
        var a = Arena.alloc(n + 1);
        for (var i = 0; i < n; i++) a.add(i).putByte((byte) s.charAt(i));
        a.add(n).putByte((byte) 0);
        return a;
    }

    /** NUL-terminated UTF-16LE string (the "W" APIs, WinHTTP, BCrypt). */
    public static Address wcstr(String s) {
        if (s == null) s = "";
        var n = s.length();
        var a = Arena.alloc(n * 2 + 2);
        for (var i = 0; i < n; i++) a.add(i * 2).putChar(s.charAt(i));
        a.add(n * 2).putChar((char) 0);
        return a;
    }

    /** Zeroed memory: structs, buffers, out-parameters. */
    public static Address alloc(int size) {
        var a = Arena.alloc(size);
        Address.fillZero(a, size);
        return a;
    }

    public static Address intVar() { return alloc(4); }
    public static Address longVar() { return alloc(8); }
    public static Address ptrVar() { return alloc(Address.sizeOf()); }

    /** Reads a NUL-terminated byte string, or null for a NULL pointer. */
    public static String string(Address p) {
        return p.toLong() == 0 ? null : string(p, Integer.MAX_VALUE);
    }

    /** Reads at most max bytes, stopping at a NUL - for fixed-size char fields such as cFileName[MAX_PATH]. */
    public static String string(Address p, int max) {
        var n = 0;
        while (n < max && p.add(n).getByte() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = (char) (p.add(i).getByte() & 0xFF);
        return new String(chars);
    }

    /** Reads a NUL-terminated UTF-16LE string (the "W" APIs), at most max chars - for resedit.c's
     *  port (PRP-20 phase 2), which is the first W-string-heavy code in this port. */
    public static String wstring(Address p, int max) {
        var n = 0;
        while (n < max && p.add(n * 2).getChar() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = p.add(i * 2).getChar();
        return new String(chars);
    }
}
