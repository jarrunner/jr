package jarrunner.jextract_teavm;

import module java.base;
import org.openjdk.jextract.Declaration.Constant;

/**
 * A function-like macro -> a plain {@code @Import}: TeaVM emits an ordinary C call and the preprocessor expands it.
 * libclang gives a macro no prototype and jextract drops function-like macros from its model, so the ARGUMENT types
 * come from the symbols line ({@code macro:LOWORD loword (int)}). The RESULT type is asked of clang: a probe parse
 * evaluates {@code sizeof(M(a))} and {@code __builtin_classify_type(M(a))} with rvalues {@code ((T)0)} of those
 * types: TeaVM may pass a constant or an expression, so a macro that needs an lvalue (takes its argument's address,
 * like macOS's WEXITSTATUS) is refused, which the same probe with lvalues {@code (*(T*)0)} tells apart. A result type written on the line ({@code macro:LOWORD loword short(int)}) is checked against clang's.
 */
public final class MacroGen {
    public static final String PREFIX = "macro:";
    static final String P = "JXTV_M_";
    static final Map<String, String> C_TYPE = Map.of("byte", "int8_t", "short", "int16_t", "char", "uint16_t",
            "int", "int32_t", "long", "int64_t", "float", "float", "double", "double", "Address", "void*");
    static final Pattern LINE = Pattern.compile("(?:(\\w+)\\s+)?(\\w*)\\s*\\((.*)\\)");
    static final List<String> HELPERS = List.of("#include <stdint.h>", "#define JXTV_STR2(x) #x", "#define JXTV_STR(x) JXTV_STR2(x)");

    record Spec(int id, String name, String javaName, String declaredResult, List<String> params) {
        String probe() {
            return P + id + "_" + name;
        }

        String display() {
            return name + "(" + String.join(", ", params) + ")";
        }

        String call() {
            return name + "(" + params.stream().map(t -> "((" + C_TYPE.get(t) + ")0)").collect(Collectors.joining(", ")) + ")";
        }

        String lvalueCall() {
            return name + "(" + params.stream().map(t -> "(*(" + C_TYPE.get(t) + "*)0)").collect(Collectors.joining(", ")) + ")";
        }
    }

    record Result(String javaType, long size, long classification) {}

    public static boolean is(String cName) {
        return cName.startsWith(PREFIX);
    }

    public static Spec parse(int id, String cName, String binding) {
        var name = cName.substring(PREFIX.length());
        var m = LINE.matcher(binding.trim());
        if (!m.matches()) throw new Unsupported("give the argument types: " + cName + " javaName (int, int), optionally with the result type before the parenthesis");
        var args = m.group(3).trim();
        var params = args.isEmpty() || args.equals("void") ? List.<String>of() : List.of(args.split("\\s*,\\s*"));
        var result = m.group(2).isEmpty() ? null : m.group(2);
        Stream.concat(params.stream(), Stream.ofNullable(result)).filter(t -> !C_TYPE.containsKey(t) && !t.equals("void")).findFirst()
                .ifPresent(t -> { throw new Unsupported("'" + t + "' is not one of " + new TreeSet<>(C_TYPE.keySet()) + " or void"); });
        if (params.contains("void")) throw new Unsupported("void is not an argument type");
        return new Spec(id, name, m.group(1) != null ? m.group(1) : name, result, params);
    }

    public static List<String> probeLines(Collection<Spec> specs) {
        if (specs.isEmpty()) return List.of();
        var lines = new ArrayList<>(HELPERS);
        for (var s : specs) {
            var n = s.probe();
            lines.addAll(List.of("#ifdef " + s.name(), "#define " + n + "_D 1", "#endif",
                    "#define %s_T JXTV_STR(%s)".formatted(n, s.name()),
                    "#define %s_K __builtin_classify_type(%s)".formatted(n, s.call()),
                    "#define %s_KL __builtin_classify_type(%s)".formatted(n, s.lvalueCall()),
                    "#define %s_S sizeof(%s)".formatted(n, s.lvalueCall())));
        }
        return lines;
    }

    /**
     * The size is probed with lvalue arguments only: jextract 25 overflows its stack on a macro such as
     * {@code sizeof(*(int*)&(1))}, which an address-taking macro given an rvalue expands to. The rvalue check is the
     * classification, which does not trip it. The result size does not depend on the arguments being lvalues.
     */
    public static Result resolve(SymbolTable probe, Spec s) {
        var n = s.probe();
        if (constant(probe, n + "_D").isEmpty()) throw new Unsupported("not a macro in any parsed header");
        if (!s.name().equals(constant(probe, n + "_T").orElse(null))) throw new Unsupported("an object-like macro: list it without " + PREFIX);
        var size = constant(probe, n + "_S").map(Long.class::cast);
        var kind = constant(probe, n + "_K").map(Long.class::cast);
        if (kind.isEmpty() && constant(probe, n + "_KL").isPresent()) throw new Unsupported(s.display()
                + " compiles only with a variable as its argument (it takes the argument's address), and TeaVM may pass a constant;"
                + " wrap it in a function in a header the C build includes, and bind that (not static: jextract skips internal linkage)");
        if (size.isEmpty() || kind.isEmpty()) throw new Unsupported(s.display()
                + " is not an expression clang can type: a different number of arguments, arguments of other types, or a statement-like macro (do/while, braces)");
        var jt = javaType(kind.get(), size.get());
        var declared = s.declaredResult();
        if (declared != null && !declared.equals(jt) && !(declared.equals("char") && jt.equals("short")))
            throw new Unsupported("yields %s (%d bytes, per clang), the symbols line says %s".formatted(jt, size.get(), declared));
        return new Result(declared != null ? declared : jt, size.get(), kind.get());
    }

    /** clang's __builtin_classify_type: 0 void, 1 integer, 2 char, 3 enum, 4 bool, 5 pointer, 8 real. */
    static String javaType(long kind, long size) {
        return switch ((int) kind) {
            case 0 -> "void";
            case 1, 2, 3, 4 -> switch ((int) size) {
                case 1 -> "byte"; case 2 -> "short"; case 4 -> "int"; case 8 -> "long";
                default -> throw new Unsupported("yields a " + size + "-byte integer, which has no Java primitive");
            };
            case 5 -> TypeMap.ADDRESS;
            case 8 -> size == 4 ? "float" : size == 8 ? "double" : bad("a " + size + "-byte floating type");
            default -> bad("a value of type class " + kind + " (a struct, union, array or complex): no scalar width");
        };
    }

    static String bad(String what) {
        throw new Unsupported("yields " + what);
    }

    static Optional<Object> constant(SymbolTable t, String name) {
        return t.find(name).filter(Constant.class::isInstance).map(d -> ((Constant) d).value());
    }

    public static Src emit(Spec s, Result r) {
        var params = new ArrayList<String>();
        for (var i = 0; i < s.params().size(); i++) params.add(s.params().get(i) + " arg" + i);
        return new Src()
                .add("    /** {@code #define %s(%d argument%s)} - a function-like macro, expanded by the C compiler at the call; result: %s, %d bytes, per clang */",
                        s.name(), s.params().size(), s.params().size() == 1 ? "" : "s", r.javaType(), r.size())
                .add("    @Import(name = \"%s\") public static native %s %s(%s);", s.name(), r.javaType(), s.javaName(), String.join(", ", params))
                .add("");
    }
}
