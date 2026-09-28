package littlejlib.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.*;

/** A struct/union -> SIZE + field offsets (clang's own layout) + typed Address accessors per scalar field. */
public final class StructGen {
    final TypeMap types;
    final Set<String> notes = new LinkedHashSet<>();

    public StructGen(TypeMap types) {
        this.types = types;
    }

    record Field(String name, long offset, Type type) {}

    public List<Field> fieldsOf(Scoped s) {
        var fields = new ArrayList<Field>();
        collect(s, 0, fields);
        return fields;
    }

    public Src emit(Scoped s, String javaName) {
        var fields = fieldsOf(s);
        var size = Attrs.sizeBits(s) / 8;
        var src = new Src()
                .add("    /** {@code %s %s} - %s, %d bytes */", s.kind().name().toLowerCase(), s.name(), Src.where(s.pos()), size)
                .add("    public static final class %s {", javaName)
                .add("        private %s() {}", javaName)
                .add("        public static final int SIZE = %d;", size);
        fields.forEach(f -> src.add("        /** {@code %s %s} */ public static final int %s = %d;",
                TypeMap.cName(f.type()), f.name(), Src.ident(f.name()), f.offset()));
        fields.forEach(f -> accessors(src, f));
        return src.add("    }").add("");
    }

    void collect(Scoped s, long base, List<Field> out) {
        for (var m : s.members()) {
            switch (m) {
                case Scoped nested when Attrs.anonymousOffsetBits(nested).isPresent() ->
                        collect(nested, base + Attrs.anonymousOffsetBits(nested).get(), out);
                case Scoped bits when bits.kind() == Scoped.Kind.BITFIELDS ->
                        notes.add(s.name() + ": bitfields skipped (" + bits.members().stream().map(Declaration::name).toList() + ")");
                case Variable v when v.kind() == Variable.Kind.FIELD -> {
                    var bitOffset = base + Attrs.offsetBits(v);
                    if (bitOffset % 8 != 0) notes.add(s.name() + "." + v.name() + ": not byte-aligned, skipped");
                    else out.add(new Field(v.name(), bitOffset / 8, v.type()));
                }
                default -> {}
            }
        }
    }

    void accessors(Src src, Field f) {
        var u = TypeMap.unqualified(f.type());
        if (u instanceof Type.Array || u instanceof Type.Declared d && d.tree().kind() != Scoped.Kind.ENUM) return;
        var jt = types.java(f.type());
        var acc = switch (jt) {
            case "Address" -> "Address";
            case "void" -> null;
            default -> Character.toUpperCase(jt.charAt(0)) + jt.substring(1);
        };
        if (acc == null) return;
        var n = Src.ident(f.name());
        src.add("        public static %s %s(Address s) { return s.add(%s).get%s(); }", jt, n, n, acc)
           .add("        public static void %s(Address s, %s v) { s.add(%s).put%s(v); }", n, jt, n, acc);
    }
}
