package jarrunner.jr.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The smallest JSON reader the update file needs, so this library has no dependencies. Objects
 *  become LinkedHashMap (source order kept), arrays List, strings String, numbers their source text
 *  as a {@link Num} holding the source text (format 1 is compared as text, the way jr does), true/false Boolean, null null. */
final class Json {
    /** A JSON number, kept as written. */
    record Num(String text) {}

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        var p = new Json(text);
        if (p.peek() == '﻿') p.i++;
        var v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("unexpected text after the value");
        return v;
    }

    /** obj.get(k1).get(k2)..., or null when any step is missing or not an object. */
    @SuppressWarnings("unchecked")
    static Object path(Object root, String... keys) {
        var v = root;
        for (var k : keys) {
            if (!(v instanceof Map)) return null;
            v = ((Map<String, Object>) v).get(k);
        }
        return v;
    }

    static String str(Object v) {
        return v instanceof String t ? t : null;
    }

    private Object value() {
        ws();
        var c = peek();
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == '-' || c >= '0' && c <= '9') return number();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        throw error("expected a value");
    }

    private Map<String, Object> object() {
        var m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw error("expected a key");
            var k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        var a = new ArrayList<Object>();
        i++;
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return a;
        }
    }

    private String string() {
        i++;
        var b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            var c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw error("unterminated string");
            var e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("bad escape \\" + e);
            }
        }
    }

    private Num number() {
        var start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return new Num(s.substring(start, i));
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(char c) {
        if (peek() != c) throw error("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + i);
    }
}
