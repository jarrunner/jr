package jarrunner.jr;

/** What -Xjr:update-check and -Xjr:update say back to an app that runs them as a child process (PRP-42).
 *  With -Xjr:batch jr shows no dialog and no progress window, prints its messages to stderr, and prints this
 *  as one JSON line on stdout: {"status":"...","version":"...","latest":"...","message":"..."}.
 *  status is one of current, newer (check only), updated, error. Exit codes do not change with -Xjr:batch:
 *  update-check 0 current, 10 newer, 1 error; update 0 updated or already current, 1 error. */
public final class UpdateResult {
    public static final String CURRENT = "current", NEWER = "newer", UPDATED = "updated", ERROR = "error";

    private UpdateResult() {}

    public static String json(String status, String version, String latest, String message) {
        var b = new StringBuilder("{\"status\":");
        quote(b, status);
        b.append(",\"version\":");
        quote(b, version);
        b.append(",\"latest\":");
        quote(b, latest);
        b.append(",\"message\":");
        quote(b, message);
        return b.append("}\n").toString();
    }

    private static void quote(StringBuilder b, String s) {
        if (s == null || s.isEmpty()) {
            b.append("null");
            return;
        }
        b.append('"');
        for (var i = 0; i < s.length(); i++) {
            var ch = s.charAt(i);
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch < 0x20) b.append("\\u00").append(hex(ch >> 4)).append(hex(ch & 15));
            else b.append(ch);
        }
        b.append('"');
    }

    private static char hex(int n) {
        return (char) (n < 10 ? '0' + n : 'a' + n - 10);
    }
}
