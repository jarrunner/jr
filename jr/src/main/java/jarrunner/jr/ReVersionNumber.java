package jarrunner.jr;

/** "1.2.3.4" (or fewer parts) -&gt; VS_FIXEDFILEINFO's MS/LS pair, mirroring resedit.c's
 *  reParseVersion. Returns null if text is not a well-formed a[.b[.c[.d]]] with each part 0-65535. */
public final class ReVersionNumber {
    private ReVersionNumber() {}

    public static int[] parse(String text) {
        var parts = new int[4];
        var i = 0;
        var n = 0;
        while (n < 4) {
            var start = i;
            while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                i++;
            }
            if (i == start) {
                return null;
            }
            var part = 0L;
            for (var k = start; k < i; k++) {
                part = part * 10 + (text.charAt(k) - '0');
            }
            if (part > 65535) {
                return null;
            }
            parts[n++] = (int) part;
            if (i < text.length() && text.charAt(i) == '.') {
                i++;
            } else {
                break;
            }
        }
        if (i != text.length()) {
            return null;
        }
        var ms = (parts[0] << 16) | parts[1];
        var ls = (parts[2] << 16) | parts[3];
        return new int[] {ms, ls};
    }
}
