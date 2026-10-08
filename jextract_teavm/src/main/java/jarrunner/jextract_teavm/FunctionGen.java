package jarrunner.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.Variable;

/** A C function -> one TeaVM {@code @Import} native method, typed from the parsed prototype. */
public final class FunctionGen {
    final TypeMap types;
    /** --text-converter: "char" / "wchar_t" -> the Java function that makes a native string; empty = no overloads. */
    Map<String, String> converters = Map.of();
    String scopeEnter, scopeExit;
    /** "function#index" of the parameters clang confirmed as read-only text (TextParams). */
    Set<String> readOnly = Set.of();
    /** --escapes-annotation: written on the parameters the symbols file marks {@code escapes=N}; null = none. */
    String escapesAnnotation;
    /** 1-based parameters of the function being emitted that the C function keeps after it returns. */
    Set<Integer> escaping = Set.of();

    public FunctionGen(TypeMap types) {
        this.types = types;
    }

    /**
     * A variadic function is bound with its fixed parameters plus {@code varargTypes}: TeaVM emits a plain C
     * call compiled against the real prototype, so the C compiler performs the variadic call convention itself.
     * {@code escaping}: parameters the C function keeps after it returns (putenv, atexit), which the headers cannot
     * say, so the symbols file does ({@code escapes=N}).
     */
    public Src emit(Declaration.Function f, String javaName, List<String> varargTypes, Set<Integer> escaping) {
        if (!varargTypes.isEmpty() && !f.type().varargs()) throw new Unsupported("extra argument types given, but not variadic");
        for (var n : escaping)
            if (n < 1 || n > f.parameters().size()) throw new Unsupported("escapes=" + n + ": there is no such parameter");
        this.escaping = escaping;
        try {
            var ret = types.java(f.type().returnType());
            var spelled = spellings(f);
            var params = paramsOf(f.parameters(), spelled);
            for (var i = 0; i < varargTypes.size(); i++) params += (params.isEmpty() ? "" : ", ") + varargTypes.get(i) + " vararg" + i;
            var c = Attrs.declString(f).orElse(f.name()).replaceAll("\\s+", " ");
            var note = f.type().varargs() ? " (variadic: fixed parameters" + (varargTypes.isEmpty() ? "" : " + " + varargTypes) + ")" : "";
            return new Src()
                    .add("    /** {@code %s} - %s%s */", c, Src.where(f.pos()), note)
                    .add("    @Import(name = \"%s\") %spublic static native %s %s(%s);", f.name(), types.ctype(f.type().returnType()), ret, javaName, params)
                    .add("")
                    .addAll(varargTypes.isEmpty() ? stringOverload(f, javaName, ret, spelled) : new Src());
        } finally {
            this.escaping = Set.of();
        }
    }

    /** "@Escapes " on a parameter the C function keeps (0-based i), else "". */
    String escapes(int i) {
        return escapesAnnotation != null && escaping.contains(i + 1) ? "@" + escapesAnnotation + " " : "";
    }

    /** Each parameter's type as clang printed it, or a list of nulls when the declaration cannot be split. */
    static List<String> spellings(Declaration.Function f) {
        var s = Attrs.declString(f).map(d -> TextParams.paramTypes(d, f)).orElse(null);
        return s != null ? s : Collections.nCopies(f.parameters().size(), null);
    }

    /**
     * The same function taking a String for each read-only text parameter, converted inside a scope that ends with
     * the call. Not generated when the function returns a pointer to characters, integers or pointers, which may point into
     * the converted copy (strchr, PathFindFileNameW): that copy is gone once the overload returns. No try/finally: a
     * native call cannot throw a Java exception, and TeaVM's C backend builds try/finally on setjmp, which costs every
     * overload a jump buffer and an exception path (measured: ~400 bytes each in jr).
     */
    Src stringOverload(Declaration.Function f, String javaName, String ret, List<String> spelled) {
        var src = new Src();
        if (converters.isEmpty() || f.type().varargs() || types.mayPointIntoText(f.type().returnType())) return src;
        var ps = f.parameters();
        var decls = new ArrayList<String>();
        var args = new ArrayList<String>();
        var used = new HashSet<String>();
        var converted = new LinkedHashSet<String>();
        for (var i = 0; i < ps.size(); i++) {
            var t = ps.get(i).type();
            var n = unique(name(ps.get(i).name(), t, i), used);
            // a parameter the function keeps is never converted: the copy is freed when the overload returns
            var conv = readOnly.contains(f.name() + "#" + i) && !escaping.contains(i + 1) ? converters.get(types.pointeeTag(t, spelled.get(i))) : null;
            decls.add(conv != null ? "String " + n : escapes(i) + types.ctype(t, spelled.get(i)) + types.java(t) + " " + n);
            args.add(conv != null ? conv + "(" + n + ")" : n);
            if (conv != null) converted.add(conv);
        }
        if (converted.isEmpty()) return src;
        var isVoid = ret.equals("void");
        src.add("    /** {@code %s} with its read-only text as Strings (%s), freed when the call returns. */", f.name(), String.join(", ", converted))
                .add("    %spublic static %s %s(%s) {", types.ctype(f.type().returnType()), ret, javaName, String.join(", ", decls))
                .add("        var scope_ = %s();", scopeEnter)
                .add("        %s%s(%s);", isVoid ? "" : "var result_ = ", javaName, String.join(", ", args))
                .add("        %s(scope_);", scopeExit);
        if (!isVoid) src.add("        return result_;");
        return src.add("    }").add("");
    }

    String paramsOf(List<Variable> ps, List<String> spelled) {
        return params(ps.stream().map(Variable::type).toList(), ps.stream().map(Variable::name).toList(), spelled);
    }

    String params(List<Type> ts, List<String> names) {
        return params(ts, names, Collections.nCopies(ts.size(), null));
    }

    /** {@code names} may be shorter than {@code ts} or hold empty strings; those are named after their typedef. */
    String params(List<Type> ts, List<String> names, List<String> spelled) {
        var used = new HashSet<String>();
        var out = new ArrayList<String>();
        for (var i = 0; i < ts.size(); i++)
            out.add(escapes(i) + types.ctype(ts.get(i), spelled.get(i)) + types.java(ts.get(i)) + " "
                    + unique(name(i < names.size() ? names.get(i) : "", ts.get(i), i), used));
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
