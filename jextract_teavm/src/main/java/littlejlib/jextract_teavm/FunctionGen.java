package littlejlib.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.Variable;

/** A C function -> one TeaVM {@code @Import} native method, typed from the parsed prototype. */
public final class FunctionGen {
    final TypeMap types;

    public FunctionGen(TypeMap types) {
        this.types = types;
    }

    /**
     * A variadic function is bound with its fixed parameters plus {@code varargTypes}: TeaVM emits a plain C
     * call compiled against the real prototype, so the C compiler performs the variadic call convention itself.
     */
    public Src emit(Declaration.Function f, String javaName, List<String> varargTypes) {
        if (!varargTypes.isEmpty() && !f.type().varargs()) throw new Unsupported("extra argument types given, but not variadic");
        var ret = types.java(f.type().returnType());
        var params = params(f.parameters());
        for (var i = 0; i < varargTypes.size(); i++) params += (params.isEmpty() ? "" : ", ") + varargTypes.get(i) + " vararg" + i;
        var c = Attrs.declString(f).orElse(f.name()).replaceAll("\\s+", " ");
        var note = f.type().varargs() ? " (variadic: fixed parameters" + (varargTypes.isEmpty() ? "" : " + " + varargTypes) + ")" : "";
        return new Src()
                .add("    /** {@code %s} - %s%s */", c, Src.where(f.pos()), note)
                .add("    @Import(name = \"%s\") public static native %s %s(%s);", f.name(), ret, javaName, params)
                .add("");
    }

    String params(List<Variable> ps) {
        return params(ps.stream().map(Variable::type).toList(), ps.stream().map(Variable::name).toList());
    }

    /** {@code names} may be shorter than {@code ts} or hold empty strings; those are named after their typedef. */
    String params(List<Type> ts, List<String> names) {
        var used = new HashSet<String>();
        var out = new ArrayList<String>();
        for (var i = 0; i < ts.size(); i++)
            out.add(types.java(ts.get(i)) + " " + unique(name(i < names.size() ? names.get(i) : "", ts.get(i), i), used));
        return String.join(", ", out);
    }

    static String name(String declared, Type type, int i) {
        if (!declared.isEmpty()) return Src.ident(declared);
        if (!(type instanceof Type.Delegated d && d.kind() == Type.Delegated.Kind.TYPEDEF)) return "arg" + i;
        var t = TypeMap.cName(type).replaceAll("\\W", "");
        if (t.isEmpty()) return "arg" + i;
        return Src.ident(t.equals(t.toUpperCase()) ? t.toLowerCase() : Character.toLowerCase(t.charAt(0)) + t.substring(1));
    }

    static String unique(String n, Set<String> used) {
        var candidate = n;
        for (var k = 2; !used.add(candidate); k++) candidate = n + k;
        return candidate;
    }
}
