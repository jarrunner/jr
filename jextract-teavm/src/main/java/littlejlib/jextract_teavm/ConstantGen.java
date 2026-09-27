package littlejlib.jextract_teavm;

import org.openjdk.jextract.Declaration.Constant;

/** A macro or enum constant -> a Java constant whose type is the C type's width, with clang's own value. */
public final class ConstantGen {
    final TypeMap types;

    public ConstantGen(TypeMap types) {
        this.types = types;
    }

    /** {@code decodedWide} is the value WideStrings recovered, for a wide literal jextract itself truncates. */
    public Src emit(Constant c, String javaName, String decodedWide) {
        var cType = TypeMap.cName(c.type());
        var decl = switch (c.value()) {
            case String s when decodedWide != null -> "String %s = %s;".formatted(javaName, Src.javaString(decodedWide));
            case String s -> "String %s = %s;".formatted(javaName, Src.javaString(s));
            case Long v -> integral(javaName, v, types.java(c.type()));
            case Double d -> types.java(c.type()).equals("float")
                    ? "float %s = %sf;".formatted(javaName, d.floatValue())
                    : "double %s = %s;".formatted(javaName, d);
            default -> throw new Unsupported("constant of unhandled kind " + c.value().getClass().getSimpleName());
        };
        var note = decodedWide != null ? "wide L\"\" literal (pass via Wstr), " : "";
        return new Src().add("    public static final %s // %s%s, %s", decl, note, cType, Src.where(c.pos()));
    }

    static String integral(String name, long v, String jt) {
        return switch (jt) {
            case "Address" -> "Address %s = Address.fromLong(%dL);".formatted(name, v);
            case "long" -> "long %s = %s;".formatted(name, v == Long.MIN_VALUE || Math.abs(v) > 4096 ? "0x%XL".formatted(v) : v + "L");
            case "int" -> "int %s = %s;".formatted(name, lit((int) v));
            case "short", "byte", "char" -> "%s %s = (%s) %s;".formatted(jt, name, jt, lit((int) v));
            default -> throw new Unsupported("integral constant typed " + jt);
        };
    }

    static String lit(int v) {
        return v >= -4096 && v <= 4096 ? Integer.toString(v) : "0x%08X".formatted(v);
    }
}
