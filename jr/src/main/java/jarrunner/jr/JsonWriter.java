package jarrunner.jr;

/** Writes a JsonValue back as compact canonical JSON: keys in source order, numbers exactly as
 *  written, pure ASCII (everything else as a backslash-u escape, so console encoding never matters). Used by -Xjr:json-dump so jr's reading of a file can
 *  be compared byte for byte with the JVM side's. Hex escapes by hand: String.format costs ~580 KB
 *  in this exe (PRP-30). */
public final class JsonWriter {
    private static final String HEX = "0123456789abcdef";

    private JsonWriter() {}

    public static String write(JsonValue v) {
        var sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, JsonValue v) {
        switch (v.kind) {
            case JsonValue.OBJECT -> {
                sb.append('{');
                for (var i = 0; i < v.size(); i++) {
                    if (i > 0) sb.append(',');
                    string(sb, v.keys.get(i));
                    sb.append(':');
                    write(sb, v.items.get(i));
                }
                sb.append('}');
            }
            case JsonValue.ARRAY -> {
                sb.append('[');
                for (var i = 0; i < v.size(); i++) {
                    if (i > 0) sb.append(',');
                    write(sb, v.items.get(i));
                }
                sb.append(']');
            }
            case JsonValue.STRING -> string(sb, v.text);
            default -> sb.append(v.text);
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c >= 0x7F) {
                        sb.append("\\u").append(HEX.charAt(c >> 12)).append(HEX.charAt((c >> 8) & 0xF))
                                .append(HEX.charAt((c >> 4) & 0xF)).append(HEX.charAt(c & 0xF));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
