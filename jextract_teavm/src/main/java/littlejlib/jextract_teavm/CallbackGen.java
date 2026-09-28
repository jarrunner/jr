package littlejlib.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;

/**
 * A pointer-to-function type (a typedef such as WNDENUMPROC, or a function's parameter such as qsort's comparator)
 * -> an abstract TeaVM {@code Function} subclass whose invoke(...) is typed from the prototype, exactly as an
 * {@code @Import} is. TeaVM's {@code Function.get(T.class, Owner.class, "method")} hands C a pointer to a static
 * Java method with that signature.
 */
public final class CallbackGen {
    final TypeMap types;
    final FunctionGen fn;

    public CallbackGen(TypeMap types, FunctionGen fn) {
        this.types = types;
        this.fn = fn;
    }

    /** The function type behind a (typedef'd, qualified) pointer, if that is what {@code t} is. */
    public static Optional<Type.Function> functionOf(Type t) {
        return TypeMap.unqualified(t) instanceof Type.Delegated d && d.kind() == Type.Delegated.Kind.POINTER
                && TypeMap.unqualified(d.type()) instanceof Type.Function f ? Optional.of(f) : Optional.empty();
    }

    public Src emit(String cName, Type.Function f, String javaName, Position pos, String declText) {
        if (f.varargs()) throw new Unsupported("variadic callback: a Java method cannot receive C varargs");
        var ret = types.java(f.returnType());
        var params = fn.params(f.argumentTypes(), f.parameterNames().orElse(List.of()));
        return new Src()
                .add("    /** {@code %s} - %s", declText != null ? declText.replaceAll("\\s+", " ") : signature(cName, f), Src.where(pos))
                .add("     *  <p>A C-callable pointer to a static Java method of this signature, called on TeaVM's own thread:")
                .add("     *  {@code (Address) (Object) Function.get(%s.class, Owner.class, \"method\")} */", javaName)
                .add("    public static abstract class %s extends Function {", javaName)
                .add("        public abstract %s invoke(%s);", ret, params)
                .add("    }")
                .add("");
    }

    static String signature(String label, Type.Function f) {
        var args = f.argumentTypes().stream().map(TypeMap::cName).collect(Collectors.joining(", "));
        var of = label.contains("(") ? ", the type of " + label.replaceAll("\\((\\d+)\\)", "'s parameter $1") : "";
        return "%s (*)(%s)%s".formatted(TypeMap.cName(f.returnType()), args, of);
    }
}
