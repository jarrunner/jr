package pocapp.jr;

/** ASCII-only case-folding, for the handful of places that only ever compare ASCII tokens (.jrc
 *  keys/values, "true"/"false", hex checksums, ".exe", env var values). String.toLowerCase()/
 *  equalsIgnoreCase() are Unicode-aware and drag in full case-folding tables regardless of which
 *  branch runs at runtime - TeaVM's dependency analysis can't tell "this call site only ever sees
 *  ASCII" from a real Unicode use, since both go through the same String method (same class of
 *  issue as java.util.regex.Pattern via String.split() - see 11-prp.01.size-experiments.md). */
public final class AsciiStr {
    private AsciiStr() {}

    public static boolean equalsIgnoreCase(String a, String b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        for (var i = 0; i < a.length(); i++) {
            if (lower(a.charAt(i)) != lower(b.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean startsWithIgnoreCase(String s, String prefix) {
        if (s.length() < prefix.length()) {
            return false;
        }
        for (var i = 0; i < prefix.length(); i++) {
            if (lower(s.charAt(i)) != lower(prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean endsWithIgnoreCase(String s, String suffix) {
        var offset = s.length() - suffix.length();
        if (offset < 0) {
            return false;
        }
        for (var i = 0; i < suffix.length(); i++) {
            if (lower(s.charAt(offset + i)) != lower(suffix.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String lower(String s) {
        var chars = new char[s.length()];
        for (var i = 0; i < s.length(); i++) {
            chars[i] = lower(s.charAt(i));
        }
        return new String(chars);
    }

    private static char lower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }
}
