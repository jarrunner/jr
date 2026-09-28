package littlejlib.jextract_teavm;

import module java.base;

/**
 * The symbol list: one C name per line, optionally followed by the Java name to bind it as.
 * Kind (function / constant / struct) is never stated - it is whatever the parsed headers say it is.
 * A variadic function may list Java types for the variadic arguments after its Java name: {@code open open3 int}.
 */
public final class Symbols {
    public static List<Map.Entry<String, String>> read(List<Path> files) throws IOException {
        var out = new ArrayList<Map.Entry<String, String>>();
        for (var file : files) for (var raw : Files.readAllLines(file)) {
            var line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            var parts = line.split("\\s+");
            out.add(Map.entry(parts[0], parts.length > 1 ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)) : parts[0]));
        }
        return out;
    }
}
