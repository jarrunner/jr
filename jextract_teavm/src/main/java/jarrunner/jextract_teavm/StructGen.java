package jarrunner.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.*;

/** A struct/union -> SIZE + field offsets (clang's own layout) + typed Address accessors per scalar field,
 *  and an Address accessor (the field's own address) per array or embedded struct field. */
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
                .add("    %spublic static final class %s {", types.ctype(s), javaName)
                .add("        private %s() {}", javaName)
                .add("        public static final int SIZE = %d;", size);
        fields.forEach(f -> src.add("        /** {@code %s %s} */ public static final int %s = %d;",
                TypeMap.cName(f.type()), f.name(), Src.ident(f.name()), f.offset()));
        var self = types.ctype(s);
        fields.forEach(f -> accessors(src, f, self));
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

    /** --buf-class: the bounds-checked buffer type (PRP-35 phase 3); null = no Buf overloads. It must offer
     *  getX(int)/putX(int, x) for each scalar accessor kind and from(int) for an embedded field. */
    String bufClass;
    /** --returned-annotation: on the struct parameter of a field-address accessor, whose result points into it. */
    String returnedAnnotation;

    void accessors(Src src, Field f, String self) {
        var u = TypeMap.unqualified(f.type());
        var n = Src.ident(f.name());
        if (u instanceof Type.Array || u instanceof Type.Declared d && d.tree().kind() != Scoped.Kind.ENUM) {
            var ret = returnedAnnotation == null ? "" : "@" + returnedAnnotation + " ";
            src.add("        public static %sAddress %s(%s%sAddress s) { return s.add(%s); }", types.ctypeOfField(f.type()), n, ret, self, n);
            if (bufClass != null) src.add("        public static %s %s(%s%s s) { return s.from(%s); }", bufClass, n, ret, bufClass, n);
            return;
        }
        var jt = types.java(f.type());
        var acc = switch (jt) {
            case "Address" -> "Address";
            case "void" -> null;
            default -> Character.toUpperCase(jt.charAt(0)) + jt.substring(1);
        };
        if (acc == null) return;
        var ptr = types.ctype(f.type());
        src.add("        public static %s%s %s(%sAddress s) { return s.add(%s).get%s(); }", ptr, jt, n, self, n, acc)
           .add("        public static void %s(%sAddress s, %s%s v) { s.add(%s).put%s(v); }", n, self, ptr, jt, n, acc);
        if (bufClass != null)
            src.add("        public static %s%s %s(%s s) { return s.get%s(%s); }", ptr, jt, n, bufClass, acc, n)
               .add("        public static void %s(%s s, %s%s v) { s.put%s(%s, v); }", n, bufClass, ptr, jt, acc, n);
    }
}
