package jarrunner.jr;

/** A small JSON reader for jr's embedded config and update files. No exceptions: a bad file comes
 *  back as a JsonValue of kind ERROR whose text says what and where. Accepts and skips a leading
 *  UTF-8 BOM (PowerShell 5.1 writes one). Hand-written because datahelper's json, measured in
 *  PRP-30, cost +147 KB in the exe at its leanest. */
public final class JsonReader {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int pos;
    private String error;

    private JsonReader(String s) {
        this.s = s;
    }

    public static JsonValue parse(String text) {
        if (text == null) {
            return new JsonValue(JsonValue.ERROR, "no input");
        }
        var r = new JsonReader(text);
        if (r.peek() == '﻿') {
            r.pos++;
        }
        var v = r.value(0);
        if (v != null) {
            r.ws();
            if (r.pos < r.s.length()) {
                r.fail("unexpected text after the end");
            }
        }
        return r.error != null ? new JsonValue(JsonValue.ERROR, r.error) : v;
    }

    private JsonValue value(int depth) {
        if (depth > MAX_DEPTH) {
            return fail("nested too deeply");
        }
        ws();
        var c = peek();
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') {
            var str = string();
            return str == null ? null : new JsonValue(JsonValue.STRING, str);
        }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        if (word("true")) return new JsonValue(JsonValue.TRUE, "true");
        if (word("false")) return new JsonValue(JsonValue.FALSE, "false");
        if (word("null")) return new JsonValue(JsonValue.NULL, "null");
        return fail(pos >= s.length() ? "unexpected end of input" : "unexpected character '" + c + "'");
    }

    private JsonValue object(int depth) {
        pos++;
        var o = new JsonValue(JsonValue.OBJECT, null);
        ws();
        if (peek() == '}') {
            pos++;
            return o;
        }
        while (true) {
            ws();
            if (peek() != '"') return fail("expected a quoted key");
            var key = string();
            if (key == null) return null;
            ws();
            if (peek() != ':') return fail("expected ':' after a key");
            pos++;
            var v = value(depth + 1);
            if (v == null) return null;
            o.put(key, v);
            ws();
            var c = peek();
            pos++;
            if (c == '}') return o;
            if (c != ',') return fail(pos - 1, "expected ',' or '}'");
        }
    }

    private JsonValue array(int depth) {
        pos++;
        var a = new JsonValue(JsonValue.ARRAY, null);
        ws();
        if (peek() == ']') {
            pos++;
            return a;
        }
        while (true) {
            var v = value(depth + 1);
            if (v == null) return null;
            a.items.add(v);
            ws();
            var c = peek();
            pos++;
            if (c == ']') return a;
            if (c != ',') return fail(pos - 1, "expected ',' or ']'");
        }
    }

    private JsonValue number() {
        var start = pos;
        if (peek() == '-') pos++;
        var lead = pos;
        if (!digits() || (s.charAt(lead) == '0' && pos - lead > 1)) return fail(lead, "bad number");
        if (peek() == '.') {
            pos++;
            if (!digits()) return fail("bad number");
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') pos++;
            if (!digits()) return fail("bad number");
        }
        return new JsonValue(JsonValue.NUMBER, s.substring(start, pos));
    }

    private String string() {
        pos++;
        var sb = new StringBuilder();
        while (pos < s.length()) {
            var c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c < 0x20) {
                fail(pos - 1, "control character inside a string");
                return null;
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            var e = pos < s.length() ? s.charAt(pos++) : 0;
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    var cp = hex4();
                    if (cp < 0) {
                        fail(pos, "bad \\u escape");
                        return null;
                    }
                    sb.append((char) cp);
                }
                default -> {
                    fail(pos - 1, "bad escape");
                    return null;
                }
            }
        }
        fail("unterminated string");
        return null;
    }

    private int hex4() {
        if (pos + 4 > s.length()) return -1;
        var v = 0;
        for (var i = 0; i < 4; i++) {
            var c = s.charAt(pos++);
            var d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (d < 0) return -1;
            v = v * 16 + d;
        }
        return v;
    }

    private boolean digits() {
        var start = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
        return pos > start;
    }

    private boolean word(String w) {
        if (!s.startsWith(w, pos)) return false;
        pos += w.length();
        return true;
    }

    private void ws() {
        while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t' || s.charAt(pos) == '\n' || s.charAt(pos) == '\r')) pos++;
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : 0;
    }

    private JsonValue fail(String msg) {
        return fail(pos, msg);
    }

    private JsonValue fail(int at, String msg) {
        if (error == null) {
            error = msg + " at line " + line(at) + ", column " + column(at);
        }
        return null;
    }

    private int line(int at) {
        var n = 1;
        for (var i = 0; i < at && i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private int column(int at) {
        var c = 1;
        for (var i = Math.min(at, s.length()) - 1; i >= 0 && s.charAt(i) != '\n'; i--) c++;
        return c;
    }
}
