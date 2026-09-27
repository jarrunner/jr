package littlejlib.jextract_teavm;

import module java.base;

/**
 * jextract evaluates a string macro through libclang's clang_EvalResult_getAsStr, which hands back the
 * literal's bytes as a NUL-terminated C string - so L"SHA256" (UTF-16: 'S',0,'H',0...) comes back as "S".
 * Recovered here without guessing: the probe parse asks clang itself for the literal's length and for each
 * code unit, as integer constant expressions (sizeof(M)/sizeof(M[0]) and M[i]), which it evaluates exactly.
 */
public final class WideStrings {
    static final int MAX = 256;
    static final String PFX = "JXTV_W_";

    public static List<String> probeLines(Collection<String> names) {
        var lines = new ArrayList<String>();
        for (var n : names) {
            var len = "(sizeof(%s)/sizeof((%s)[0]))".formatted(n, n);
            lines.add("#define %s%s_N %s".formatted(PFX, n, len));
            for (var i = 0; i < MAX; i++)
                lines.add("#define %s%s_%d (%s > %d ? (%s)[%d] : 0)".formatted(PFX, n, i, len, i, n, i));
        }
        return lines;
    }

    public static Map<String, String> read(SymbolTable probe, Collection<String> names) {
        var out = new LinkedHashMap<String, String>();
        for (var n : names) {
            var count = value(probe, PFX + n + "_N") - 1;
            if (count < 0 || count > MAX) throw new Unsupported(n + ": wide literal length " + count + " not recoverable");
            var b = new StringBuilder();
            for (var i = 0; i < count; i++) b.appendCodePoint((int) value(probe, PFX + n + "_" + i));
            out.put(n, b.toString());
        }
        return out;
    }

    static long value(SymbolTable t, String name) {
        return MacroGen.constant(t, name).filter(Long.class::isInstance).map(Long.class::cast)
                .orElseThrow(() -> new Unsupported("clang could not evaluate " + name));
    }
}
