package jarrunner.jr;

import java.util.function.Supplier;
import org.teavm.interop.Address;

/** Native memory for PosixApi calls, used as {@code import static jarrunner.jr.N.*;}. Names follow Kotlin/Native and C.
 *  Everything is allocated off the TeaVM heap (see {@link Arena}), so no GC can free or move it under a pointer, and
 *  allocating Java objects between taking a pointer and the call that uses it is harmless. Memory lives until the
 *  innermost {@link #memScoped} ends, or for the whole program outside any scope. Never store a pointer from here
 *  in a field, and never use one after its scope. Never pass {@code Address.ofData} of a Java array to native code;
 *  use {@link #alloc} instead (prp/18-prp.01 has the measurements behind these rules).
 *
 *  <p>Strings (PRP-34). On Linux and macOS the native encoding of paths, the environment, arguments and text is
 *  UTF-8, so {@link #utf8} and {@link #string} convert both ways, by hand: the JDK charset path costs exe size, and
 *  the C library's conversions follow the locale, which is "C" for an app started without LANG (a macOS app opened
 *  from Finder). {@link #ascii} is only for protocol tokens (CRT mode strings): never pass it a path or user text. */
public final class N {
    private N() {}

    public static final Address NULL = Address.fromInt(0);

    public static <T> T memScoped(Supplier<T> body) {
        var mark = mark();
        try { return body.get(); } finally { release(mark); }
    }

    public static void memScoped(Runnable body) {
        var mark = mark();
        try { body.run(); } finally { release(mark); }
    }

    /** The scope primitives behind memScoped, without a lambda: everything allocated after mark() is freed by
     *  release(mark). The generated String overloads in WinApi/PosixApi use these (PRP-35). */
    public static long mark() { return Arena.mark(); }

    public static void release(long mark) { Arena.reset(mark); }

    /** NUL-terminated ASCII, for protocol tokens only (see the class comment). A char above 0x7F becomes '?'. */
    public static @CType("char") Address ascii(String s) {
        var n = s.length();
        var a = Arena.alloc(n + 1);
        for (var i = 0; i < n; i++) {
            var c = s.charAt(i);
            a.add(i).putByte((byte) (c < 0x80 ? c : '?'));
        }
        a.add(n).putByte((byte) 0);
        return a;
    }

    /** NUL-terminated UTF-8: paths, environment, arguments, file text. An unpaired surrogate becomes U+FFFD. */
    public static @CType("char") Address utf8(String s) {
        if (s == null) s = "";
        var a = Arena.alloc(s.length() * 3 + 1);
        var n = 0;
        for (var i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c >= 0xD800 && c <= 0xDFFF) {
                var lo = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                if (c <= 0xDBFF && lo >= 0xDC00 && lo <= 0xDFFF) {
                    c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                    i++;
                } else {
                    c = 0xFFFD;
                }
            }
            if (c < 0x80) {
                a.add(n++).putByte((byte) c);
            } else if (c < 0x800) {
                a.add(n++).putByte((byte) (0xC0 | c >> 6));
                a.add(n++).putByte((byte) (0x80 | c & 0x3F));
            } else if (c < 0x10000) {
                a.add(n++).putByte((byte) (0xE0 | c >> 12));
                a.add(n++).putByte((byte) (0x80 | c >> 6 & 0x3F));
                a.add(n++).putByte((byte) (0x80 | c & 0x3F));
            } else {
                a.add(n++).putByte((byte) (0xF0 | c >> 18));
                a.add(n++).putByte((byte) (0x80 | c >> 12 & 0x3F));
                a.add(n++).putByte((byte) (0x80 | c >> 6 & 0x3F));
                a.add(n++).putByte((byte) (0x80 | c & 0x3F));
            }
        }
        a.add(n).putByte((byte) 0);
        return a;
    }

    /** Zeroed memory: structs, buffers, out-parameters. */
    public static Address alloc(int size) {
        var a = Arena.alloc(size);
        Address.fillZero(a, size);
        return a;
    }

    public static @CType("int16") Address shortVar() { return alloc(2); }
    public static @CType("int32") Address intVar() { return alloc(4); }
    public static @CType("int64") Address longVar() { return alloc(8); }
    public static @CType("pointer") Address ptrVar() { return alloc(Address.sizeOf()); }

    /** The value of an out-parameter from the *Var() functions above: the whole variable, at offset 0, so no pointer
     *  arithmetic happens outside N (PRP-35 rule NC4). */
    public static short shortOf(@CType("int16") Address v) { return v.getShort(); }
    public static int intOf(@CType("int32") Address v) { return v.getInt(); }
    public static long longOf(@CType("int64") Address v) { return v.getLong(); }
    public static Address ptrOf(@CType("pointer") Address v) { return v.getAddress(); }
    public static void setInt(@CType("int32") Address v, int x) { v.putInt(x); }

    /** n bytes of native memory copied into a Java array, where every later read is bounds-checked. */
    public static byte[] bytesOf(Address p, int n) {
        var b = new byte[n];
        for (var i = 0; i < n; i++) b[i] = p.add(i).getByte();
        return b;
    }

    /** Reads a NUL-terminated UTF-8 string, or null for a NULL pointer. */
    public static String string(@Nullable @CType("char") Address p) {
        return p.toLong() == 0 ? null : string(p, Integer.MAX_VALUE);
    }

    /** Reads at most max bytes of UTF-8, stopping at a NUL - also for fixed-size fields such as d_name[256]. */
    public static String string(@CType("char") Address p, int max) {
        var n = 0;
        while (n < max && p.add(n).getByte() != 0) n++;
        return text(p, n);
    }

    /** UTF-8 bytes as text: a leading BOM is dropped, a malformed byte becomes U+FFFD rather than failing the read. */
    public static String text(Address p, int n) {
        var i = n >= 3 && (p.getByte() & 0xFF) == 0xEF && (p.add(1).getByte() & 0xFF) == 0xBB
                && (p.add(2).getByte() & 0xFF) == 0xBF ? 3 : 0;
        var sb = new StringBuilder(n);
        while (i < n) {
            var b = p.add(i++).getByte() & 0xFF;
            var extra = b < 0x80 ? 0 : b >= 0xF0 && b < 0xF5 ? 3 : b >= 0xE0 && b < 0xF0 ? 2 : b >= 0xC2 && b < 0xE0 ? 1 : -1;
            if (extra < 0) {
                sb.append('�');
                continue;
            }
            var cp = extra == 0 ? b : b & (0x3F >> extra);
            var ok = true;
            for (var k = 0; k < extra; k++) {
                if (i >= n || (p.add(i).getByte() & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
                cp = (cp << 6) | (p.add(i++).getByte() & 0x3F);
            }
            var min = extra == 1 ? 0x80 : extra == 2 ? 0x800 : 0x10000;
            if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) || (extra > 0 && cp < min)) {
                sb.append('�');
            } else if (cp >= 0x10000) {
                cp -= 0x10000;
                sb.append((char) (0xD800 + (cp >> 10))).append((char) (0xDC00 + (cp & 0x3FF)));
            } else {
                sb.append((char) cp);
            }
        }
        return sb.toString();
    }

    /** An open OS resource (a handle, a module, a FILE*) is no longer this code's to close: something the checker cannot
     *  see now owns it (the C runtime adopted it as an fd), or it stays open on purpose until the process exits (a
     *  library still in use). Does nothing at run time; it is how the code says so to teavm_native_check (NC8, its
     *  takes= list). Say why at the call. */
    public static void handOver(Address resource) {}
}
