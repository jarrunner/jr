package pocapp.jr;

import org.teavm.interop.Address;

/** UTF-16LE ("wide") string helper for WinHTTP/BCrypt, which are Unicode-only APIs with no ANSI
 *  variant - unlike everything else in this port, which goes through the ANSI Cstr helper. */
public final class Wstr {
    private Wstr() {}

    public static Address of(String s) {
        if (s == null) {
            s = "";
        }
        var b = new byte[(s.length() + 1) * 2];
        for (int i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            b[i * 2] = (byte) (c & 0xFF);
            b[i * 2 + 1] = (byte) ((c >> 8) & 0xFF);
        }
        return Address.ofData(b);
    }
}
