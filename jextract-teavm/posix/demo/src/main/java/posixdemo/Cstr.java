package posixdemo;

import org.teavm.interop.Address;

/**
 * Strings cross into C as NUL-terminated byte[] held by the CALLER, and are turned into an Address only inside
 * the call expression ({@code open(at(path), ...)}): no allocation may happen between taking an address of a heap
 * array and the native call using it, or a GC in between can free or move the array under the pointer.
 */
public final class Cstr {
    private Cstr() {}

    public static byte[] z(String s) {
        var b = new byte[s.length() + 1];
        for (var i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    public static Address at(byte[] b) {
        return Address.ofData(b);
    }

    public static String read(Address p) {
        if (p == null || p.toLong() == 0) return null;
        var n = 0;
        while (p.add(n).getByte() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = (char) (p.add(i).getByte() & 0xFF);
        return new String(chars);
    }
}
