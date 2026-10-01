package jarrunner.jextract_teavm;

import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.Scoped;
import org.openjdk.jextract.Type.*;

import static org.openjdk.jextract.Type.Primitive.Kind.*;

/** C type -> the Java type a TeaVM {@code @Import} (or an {@code Address} accessor) must use for it. */
public final class TypeMap {
    public static final String ADDRESS = "Address";
    final DataModel dm;

    public TypeMap(DataModel dm) {
        this.dm = dm;
    }

    public String java(Type t) {
        return switch (t) {
            case Primitive p -> primitive(p.kind());
            case Delegated d -> switch (d.kind()) {
                case POINTER -> ADDRESS;
                case COMPLEX -> throw new Unsupported("_Complex has no Java counterpart");
                default -> java(d.type());
            };
            case Declared d -> declared(d.tree());
            case Array a -> ADDRESS;
            case Type.Function f -> throw new Unsupported("bare function type (not a pointer to one)");
            default -> throw new Unsupported("unhandled C type " + t);
        };
    }

    String primitive(Primitive.Kind k) {
        if (k == Void) return "void";
        if (k == Float) return "float";
        if (k == Double) return "double";
        if (k == WChar || k == Char16) return dm.size(k) == 2 ? "char" : "int";
        return switch (dm.size(k)) {
            case 1 -> "byte";
            case 2 -> "short";
            case 4 -> "int";
            case 8 -> "long";
            default -> throw new Unsupported(k.typeName() + " (" + dm.size(k) + " bytes) has no Java primitive");
        };
    }

    String declared(Scoped s) {
        if (s.kind() == Scoped.Kind.ENUM) return Attrs.enumType(s).map(this::java).orElse("int");
        throw new Unsupported(s.kind().name().toLowerCase() + " " + s.name()
                + " passed BY VALUE - TeaVM passes a Structure only as a pointer (void*) in either direction;"
                + " bind a one-line header macro taking pointers instead, with macro: (see README, windemo/byvalue-shim.h)");
    }

    /** Size in bytes of a scalar C type, for picking an Address getX()/putX() pair. */
    public int scalarSize(Type t) {
        return switch (java(t)) {
            case "byte" -> 1;
            case "short", "char" -> 2;
            case "int", "float" -> 4;
            default -> 8;
        };
    }

    public static boolean isWideString(Type t, DataModel dm) {
        return pointee(t) instanceof Primitive p && dm.size(p.kind()) > 1;
    }

    static Type pointee(Type t) {
        return switch (t) {
            case Delegated d when d.kind() == Delegated.Kind.POINTER -> unqualified(d.type());
            case Delegated d -> pointee(d.type());
            default -> null;
        };
    }

    public static Type unqualified(Type t) {
        return t instanceof Delegated d && d.kind() != Delegated.Kind.POINTER ? unqualified(d.type()) : t;
    }

    /** The C spelling a human recognises: the outermost typedef name, else a rebuilt spelling. */
    public static String cName(Type t) {
        return switch (t) {
            case Delegated d when d.kind() == Delegated.Kind.TYPEDEF && d.name().isPresent() -> d.name().get();
            case Delegated d when d.kind() == Delegated.Kind.POINTER -> cName(d.type()) + "*";
            case Delegated d -> d.kind().name().toLowerCase() + " " + cName(d.type());
            case Primitive p -> p.kind().typeName();
            case Declared d -> d.tree().kind().name().toLowerCase() + " " + d.tree().name();
            case Array a -> cName(a.elementType()) + "[" + (a.elementCount().isPresent() ? a.elementCount().getAsLong() : "") + "]";
            default -> t.toString();
        };
    }
}
