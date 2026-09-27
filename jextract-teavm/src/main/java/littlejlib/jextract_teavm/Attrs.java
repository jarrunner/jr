package littlejlib.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.Scoped;

/**
 * jextract's layout facts (clang sizeof/offsetof, the C declaration text, nested and anonymous records) live
 * in package-private attribute records reachable only through the public Declaration.attributes(). Read by
 * record name and first component; needs --add-opens org.openjdk.jextract/org.openjdk.jextract.impl.
 */
public final class Attrs {
    static Optional<Object> get(Declaration d, String record) {
        return d.attributes().stream().filter(r -> r.getClass().getSimpleName().equals(record)).findFirst().map(Attrs::first);
    }

    static Object first(Record r) {
        try {
            var m = r.getClass().getRecordComponents()[0].getAccessor();
            m.setAccessible(true);
            return m.invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read jextract attribute " + r, e);
        }
    }

    public static long sizeBits(Declaration d) {
        return (Long) get(d, "ClangSizeOf").orElseThrow(() -> new Unsupported(d.name() + ": no size (incomplete type?)"));
    }

    public static long offsetBits(Declaration d) {
        return (Long) get(d, "ClangOffsetOf").orElseThrow(() -> new Unsupported(d.name() + ": no offset"));
    }

    public static Optional<String> declString(Declaration d) {
        return get(d, "DeclarationString").map(String.class::cast);
    }

    @SuppressWarnings("unchecked")
    public static List<Scoped> nested(Declaration d) {
        return (List<Scoped>) get(d, "NestedDeclarations").orElse(List.of());
    }

    public static Optional<Type> enumType(Scoped s) {
        return get(s, "ClangEnumType").map(Type.class::cast);
    }

    /** Empty when the record is not anonymous; else its bit offset in the parent. */
    public static Optional<Long> anonymousOffsetBits(Scoped s) {
        return get(s, "AnonymousStruct").map(o -> ((OptionalLong) o).orElse(0));
    }
}
