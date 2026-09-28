package memlab.ffi;

import org.teavm.interop.Address;

public final class Mem {
    static final Mem M = new Mem();

    private Mem() {}

    public Address c(String s) {
        if (s == null) return Address.fromInt(0);
        var n = s.length();
        var a = Arena.alloc(n + 1);
        for (var i = 0; i < n; i++) a.add(i).putByte((byte) s.charAt(i));
        a.add(n).putByte((byte) 0);
        return a;
    }

    public Address w(String s) {
        if (s == null) return Address.fromInt(0);
        var n = s.length();
        var a = Arena.alloc(n * 2 + 2);
        for (var i = 0; i < n; i++) a.add(i * 2).putChar(s.charAt(i));
        a.add(n * 2).putChar((char) 0);
        return a;
    }

    public Address buf(int n) {
        var a = Arena.alloc(n);
        Address.fillZero(a, n);
        return a;
    }

    public Address int32() { return buf(4); }
    public Address int64() { return buf(8); }
    public Address ptr() { return buf(Address.sizeOf()); }

    public Address ptrs(String... items) {
        var arr = buf((items.length + 1) * Address.sizeOf());
        for (var i = 0; i < items.length; i++) arr.add(i * Address.sizeOf()).putAddress(c(items[i]));
        return arr;
    }

    public String str(Address p) {
        if (p.toLong() == 0) return null;
        var n = 0;
        while (p.add(n).getByte() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = (char) (p.add(i).getByte() & 0xFF);
        return new String(chars);
    }

    public String wstr(Address p) {
        if (p.toLong() == 0) return null;
        var n = 0;
        while (p.add(n * 2).getChar() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = p.add(i * 2).getChar();
        return new String(chars);
    }
}
