package jarrunner.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;

/**
 * Which char / wchar_t pointer parameters are read-only text. jextract's type model drops const, and a typedef
 * hides it anyway (LPCWSTR is const, LPWSTR is not), so clang itself is asked, in the probe parse, with the
 * parameter's type spelled as clang printed it: {@code _Generic((T)0, const __typeof__(*(T)0) *: 1, default: 0)} is 1
 * exactly when T points to const. Anything clang cannot answer counts as writable, which is the safe side: only
 * read-only text gets a String overload, because the converted copy is freed when the call returns.
 */
public final class TextParams {
    static final String PFX = "JXTV_C_";

    record Param(String function, int index, String cType) {
        String macro() { return PFX + function + "_" + index; }
    }

    /** The text parameters of f, or none if its declaration text cannot be split into exactly its parameters. */
    public static List<Param> of(Declaration.Function f, TypeMap types) {

        var spelled = Attrs.declString(f).map(d -> paramTypes(d, f)).orElse(null);
        if (spelled == null) return List.of();
        var out = new ArrayList<Param>();
        for (var i = 0; i < f.parameters().size(); i++)
            if (types.isText(f.parameters().get(i).type(), spelled.get(i))) out.add(new Param(f.name(), i, spelled.get(i)));
        return out;
    }

    public static List<String> probeLines(Collection<Param> ps) {
        return ps.stream().map(p -> "#define %s _Generic(((%s)0), const __typeof__(*((%s)0)) *: 1, default: 0)"
                .formatted(p.macro(), p.cType(), p.cType())).toList();
    }

    /** "function#index" of every parameter clang confirmed as pointer to const. */
    public static Set<String> readOnly(SymbolTable probe, Collection<Param> ps) {
        var out = new HashSet<String>();
        for (var p : ps)
            if (MacroGen.constant(probe, p.macro()).filter(Long.valueOf(1)::equals).isPresent()) out.add(p.function() + "#" + p.index());
        return out;
    }

    /** Each parameter's type as clang printed the declaration, name removed; null if the split does not add up. */
    static List<String> paramTypes(String decl, Declaration.Function f) {
        var open = decl.indexOf(f.name() + "(");
        if (open < 0) return null;
        var pieces = new ArrayList<String>();
        var depth = 0;
        var start = open + f.name().length() + 1;
        for (var i = start; i < decl.length(); i++) {
            var c = decl.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth-- == 0) {
                pieces.add(decl.substring(start, i).trim());
                break;
            } else if (c == ',' && depth == 0) {
                pieces.add(decl.substring(start, i).trim());
                start = i + 1;
            }
        }
        var ps = f.parameters();
        if (pieces.size() != ps.size()) return null;
        var out = new ArrayList<String>();
        for (var i = 0; i < ps.size(); i++) {
            var piece = pieces.get(i);
            var name = ps.get(i).name();
            out.add(!name.isEmpty() && piece.matches(".*\\W" + Pattern.quote(name)) ? piece.substring(0, piece.length() - name.length()).trim() : piece);
        }
        return out;
    }
}
