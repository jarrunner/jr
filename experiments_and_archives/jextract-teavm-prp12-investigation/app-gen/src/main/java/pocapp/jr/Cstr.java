package pocapp.jr;

import org.teavm.interop.Address;

/** Address <-> String conversion helpers - every native call in this port goes through these. */
public final class Cstr {
    private Cstr() {}

    public static Address of(String s) {
        if (s == null) {
            s = "";
        }
        var b = new byte[s.length() + 1];
        for (int i = 0; i < s.length(); i++) {
            b[i] = (byte) s.charAt(i);
        }
        b[s.length()] = 0;
        return Address.ofData(b);
    }

    public static String read(Address p) {
        if (p == null || p.toLong() == 0) {
            return null;
        }
        var sb = new StringBuilder();
        var i = 0;
        while (true) {
            var b = p.add(i).getByte();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
            i++;
        }
        return sb.toString();
    }

    public static String readEnv(String name) {
        return read(WinApi.getenv(of(name)));
    }
}
