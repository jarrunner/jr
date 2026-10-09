package jarrunner.jr;

/** Percent-encoding for a mailto: or issue-tracker link (PRP-31), UTF-8, no java.net (size). */
public final class Url {
    private Url() {}

    private static final String HEX = "0123456789ABCDEF";

    static String encode(String s) {
        var sb = new StringBuilder();
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else if (c < 0x80) {
                pct(sb, c);
            } else if (c < 0x800) {
                pct(sb, 0xC0 | c >> 6);
                pct(sb, 0x80 | c & 0x3F);
            } else {
                pct(sb, 0xE0 | c >> 12);
                pct(sb, 0x80 | c >> 6 & 0x3F);
                pct(sb, 0x80 | c & 0x3F);
            }
        }
        return sb.toString();
    }

    private static void pct(StringBuilder sb, int b) {
        sb.append('%').append(HEX.charAt(b >> 4 & 0xF)).append(HEX.charAt(b & 0xF));
    }
}
