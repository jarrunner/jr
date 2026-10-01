package jarrunner.jr;

/** Decodes UTF-8 by hand from FileIo.readAll's one-char-per-byte string (the JDK charset path
 *  costs exe size). Malformed bytes become U+FFFD rather than failing the read. */
public final class Utf8 {
    private Utf8() {}

    public static String decode(String bytes) {
        if (bytes == null) {
            return null;
        }
        var n = bytes.length();
        var sb = new StringBuilder(n);
        var i = 0;
        while (i < n) {
            var b = bytes.charAt(i++) & 0xFF;
            var extra = b < 0x80 ? 0 : b >= 0xF0 && b < 0xF8 ? 3 : b >= 0xE0 ? 2 : b >= 0xC2 && b < 0xE0 ? 1 : -1;
            if (extra < 0) {
                sb.append('�');
                continue;
            }
            var cp = extra == 0 ? b : b & (0x3F >> extra);
            var ok = true;
            for (var k = 0; k < extra; k++) {
                if (i >= n || (bytes.charAt(i) & 0xC0) != 0x80) {
                    ok = false;
                    break;
                }
                cp = (cp << 6) | (bytes.charAt(i++) & 0x3F);
            }
            if (!ok || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
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
}
