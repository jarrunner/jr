package memlab;

import org.teavm.interop.Address;

public final class Old {
    private Old() {}

    public static Address cstr(String s) {
        var b = new byte[s.length() + 1];
        for (var i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return Address.ofData(b);
    }

    public static Address cstrAfterGc(String s) {
        Gc.stormAndRefill();
        return cstr(s);
    }

    public static byte[] bytes(String s) {
        var b = new byte[s.length() + 1];
        for (var i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    public static String read(Address p) {
        if (p.toLong() == 0) return null;
        var n = 0;
        while (p.add(n).getByte() != 0 && n < 60) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = (char) (p.add(i).getByte() & 0xFF);
        return new String(chars);
    }
}
