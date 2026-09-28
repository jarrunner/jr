package littlejlib.jr;

/** C's atoi(): the leading (optionally signed) run of digits, 0 if there is none - never throws,
 *  unlike Integer.parseInt(). "21+" -&gt; 21, "1.8.0_402" -&gt; 1, "" -&gt; 0. Needed because java.version
 *  values ("21+") and JDK release-file versions ("25.0.1", legacy "1.8.0_402") both carry trailing
 *  content after the number that matters elsewhere but must not make the parse fail. */
public final class Atoi {
    private Atoi() {}

    public static int parse(String s) {
        if (s == null) {
            return 0;
        }
        var i = 0;
        var n = s.length();
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        var neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        var start = i;
        var value = 0L;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            value = value * 10 + (s.charAt(i) - '0');
            i++;
        }
        if (i == start) {
            return 0;
        }
        return (int) (neg ? -value : value);
    }
}
