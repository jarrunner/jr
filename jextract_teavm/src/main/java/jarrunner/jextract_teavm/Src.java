package jarrunner.jextract_teavm;

import module java.base;

/** Line-oriented Java source accumulator. */
public final class Src {
    final List<String> lines = new ArrayList<>();

    public Src add(String fmt, Object... args) {
        lines.add(args.length == 0 ? fmt : fmt.formatted(args));
        return this;
    }

    public Src addAll(Src other) {
        lines.addAll(other.lines);
        return this;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public String text() {
        return String.join("\n", lines) + "\n";
    }

    static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
            "native", "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "var", "record", "yield", "sealed", "permits", "true", "false", "null", "_");

    public static String ident(String name) {
        return KEYWORDS.contains(name) ? name + "_" : name;
    }

    public static String javaString(String s) {
        var b = new StringBuilder("\"");
        s.chars().forEach(c -> b.append(switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            default -> c < 0x20 || c > 0x7e ? "\\u%04x".formatted(c) : String.valueOf((char) c);
        }));
        return b.append('"').toString();
    }

    public static String where(org.openjdk.jextract.Position p) {
        return p.path() == null ? "?" : p.path().getFileName() + ":" + p.line();
    }
}
