package jarrunner.jr;

import java.util.List;

/** Extracts a jar file path from either a config.javaArgs-style string or a token list, mirroring
 *  launcher.c's extractJarPath (used only to name the AOT cache - not required to be present). */
public final class JarPath {
    private JarPath() {}

    /** From a raw "-jar path/to/x.jar ..." style string (config.javaArgs). */
    public static String fromArgsString(String javaArgs) {
        if (javaArgs == null || javaArgs.isBlank()) {
            return "";
        }
        var tokens = splitOnWhitespace(javaArgs);
        for (var i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).equals("-jar")) {
                return stripQuotes(tokens.get(i + 1));
            }
        }
        return "";
    }

    /** Hand-rolled whitespace tokenizer - avoids pulling java.util.regex (Pattern/Matcher, a real
     *  size contributor in the TeaVM C backend - see 11-prp.01.size-experiments.md) into the build
     *  just to split on "\\s+", which is the only thing this needed regex for. */
    private static List<String> splitOnWhitespace(String s) {
        var out = new java.util.ArrayList<String>();
        var start = -1;
        var quoted = false; // PRP-34: -jar "C:\Program Files\app.jar" was cut at the space, so no AOT cache
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') {
                quoted = !quoted;
            }
            if (!quoted && Character.isWhitespace(s.charAt(i))) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            out.add(s.substring(start));
        }
        return out;
    }

    /** From an already-tokenized arg list (traditional mode): "-jar X" if present, else the
     *  first token that looks like a bare jar path. */
    public static String fromTokens(List<String> tokens) {
        for (var i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i).equals("-jar")) {
                return stripQuotes(tokens.get(i + 1));
            }
        }
        return tokens.isEmpty() ? "" : stripQuotes(tokens.get(0));
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
