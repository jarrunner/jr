package pocapp.jr;

import org.teavm.interop.Address;

/** GetCurrentDirectoryA wrapper. */
public final class Cwd {
    private Cwd() {}

    public static String get() {
        var buf = new byte[1024];
        var addr = Address.ofData(buf);
        var len = WinApi.getCurrentDirectoryA(buf.length, addr);
        if (len <= 0) {
            return "";
        }
        var sb = new StringBuilder();
        for (var i = 0; i < len; i++) {
            sb.append((char) (buf[i] & 0xFF));
        }
        return sb.toString();
    }
}
