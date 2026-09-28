package memlab.nat;

import java.util.function.Supplier;
import org.teavm.interop.Address;

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

    public static Address cstr(String s) {
        if (s == null) return NULL;
        var n = s.length();
        var a = Arena.alloc(n + 1);
        for (var i = 0; i < n; i++) a.add(i).putByte((byte) s.charAt(i));
        a.add(n).putByte((byte) 0);
        return a;
    }

    public static Address wcstr(String s) {
        if (s == null) return NULL;
        var n = s.length();
        var a = Arena.alloc(n * 2 + 2);
        for (var i = 0; i < n; i++) a.add(i * 2).putChar(s.charAt(i));
        a.add(n * 2).putChar((char) 0);
        return a;
    }

    public static Address cstrArray(String... items) {
        var p = Address.sizeOf();
        var arr = alloc((items.length + 1) * p);
        for (var i = 0; i < items.length; i++) arr.add(i * p).putAddress(cstr(items[i]));
        return arr;
    }

    public static Address alloc(int size) {
        var a = Arena.alloc(size);
        Address.fillZero(a, size);
        return a;
    }

    public static Address intVar() { return alloc(4); }
    public static Address longVar() { return alloc(8); }
    public static Address ptrVar() { return alloc(Address.sizeOf()); }

    public static String string(Address p) {
        if (p.toLong() == 0) return null;
        var n = 0;
        while (p.add(n).getByte() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = (char) (p.add(i).getByte() & 0xFF);
        return new String(chars);
    }

    public static String wstring(Address p) {
        if (p.toLong() == 0) return null;
        var n = 0;
        while (p.add(n * 2).getChar() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = p.add(i * 2).getChar();
        return new String(chars);
    }

    public static void checkLeaks() {
        if (Arena.top != 0 || Arena.bigN != 0)
            throw new IllegalStateException("native memory still held: " + Arena.top + " bytes, " + Arena.bigN + " chunks");
    }
}
