package jarrunner.jextract_teavm;

import java.util.regex.Pattern;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.Scoped;
import org.openjdk.jextract.Type.*;

import static org.openjdk.jextract.Type.Primitive.Kind.*;

/** C type -> the Java type a TeaVM {@code @Import} (or an {@code Address} accessor) must use for it. */
public final class TypeMap {
    public static final String ADDRESS = "Address";
    final DataModel dm;
    final String ctypeAnnotation;

    public TypeMap(DataModel dm, String ctypeAnnotation) {
        this.dm = dm;
        this.ctypeAnnotation = ctypeAnnotation;
    }

    /** {@code @CType("...") } when --ctype-annotation is on and t is a pointer whose target is known, else "", so a
     *  checker can tell one kind of pointer from another (teavm_native_check NC6): "struct _X" or "union _X", "char" or
     *  "wchar_t" for text, "int16".."int64" / "float" / "double" for a scalar by width, "pointer" for a pointer; nothing
     *  for void* or a byte buffer (unsigned char*), which take any memory. */
    public String ctype(Type t) {
        return ctype(t, null);
    }

    /** spelling: the parameter's type as clang printed the declaration, when known (see isWide). */
    public String ctype(Type t, String spelling) {
        return annotation(pointeeTag(t, spelling));
    }

    /** A pointer to char or wchar_t: text, for the String overloads. */
    public boolean isText(Type t, String spelling) {
        var tag = pointeeTag(t, spelling);
        return "char".equals(tag) || "wchar_t".equals(tag);
    }

    /** A pointer to a character or integer type, or to another pointer: it could point into the caller's string
     *  (strchr, PathFindFileNameW) or at an array of such pointers. Handles, void*, structs and functions are not. */
    public boolean mayPointIntoText(Type t) {
        var raw = rawPointee(t);
        return raw != null && switch (unqualified(raw)) {
            case Primitive p -> p.kind() != Void;
            case Delegated d -> d.kind() == Delegated.Kind.POINTER;
            default -> false;
        };
    }

    String pointeeTag(Type t, String spelling) {
        var raw = rawPointee(t);
        return raw == null ? null : switch (unqualified(raw)) {
            case Declared d -> tag(d.tree());
            case Delegated d when d.kind() == Delegated.Kind.POINTER -> "pointer";
            case Primitive p when p.kind() != Char && p.kind() != Void && isWide(t, spelling) -> "wchar_t";
            case Primitive p -> scalarTag(p.kind(), explicitSign(raw));
            default -> null;
        };
    }

    String scalarTag(Primitive.Kind k, boolean signedOrUnsigned) {
        if (k == Char) return signedOrUnsigned ? null : "char"; // unsigned char* (PUCHAR, BYTE*) is a byte buffer, as untyped as void*
        if (k == WChar || k == Char16) return "wchar_t";
        if (k == Float || k == Double) return k == Float ? "float" : "double";
        if (k == Void || k == LongDouble || k == Bool) return k == Bool ? "int8" : null;
        return "int" + 8 * dm.size(k);
    }

    /** The pointed-to type with its typedefs and signedness still on it. */
    static Type rawPointee(Type t) {
        return switch (t) {
            case Delegated d when d.kind() == Delegated.Kind.POINTER -> d.type();
            case Delegated d -> rawPointee(d.type());
            default -> null;
        };
    }

    /** Wide text can only be known by name. In C, wchar_t is a typedef of an integer type (unsigned short on Windows,
     *  where LPCWSTR and USHORT* are therefore one type to clang), and jextract records WCHAR straight to that integer.
     *  So a pointer is wide text when a typedef on its way down, or the parameter's printed spelling, is named
     *  wchar_t or WCHAR or ends in WSTR or WCH (LPCWSTR, PWSTR, LPCWCH): the headers' own names, not a guess. */
    static final Pattern WIDE = Pattern.compile("\\b(wchar_t|WCHAR|\\w*WSTR|\\w*WCH)\\b");

    static boolean isWide(Type t, String spelling) {
        return spelling != null && WIDE.matcher(spelling).find() || typedefNamed(t);
    }

    static boolean typedefNamed(Type t) {
        return t instanceof Delegated d && (d.name().filter(n -> WIDE.matcher(n).matches()).isPresent() || typedefNamed(d.type()));
    }

    /** True if signed or unsigned was spelled out (unsigned char is a byte, plain char is text). */
    static boolean explicitSign(Type t) {
        return t instanceof Delegated d && (d.kind() == Delegated.Kind.SIGNED || d.kind() == Delegated.Kind.UNSIGNED
                || d.kind() != Delegated.Kind.POINTER && explicitSign(d.type()));
    }

    /** The same annotation for a pointer to s itself. */
    public String ctype(Scoped s) {
        return annotation(tag(s));
    }

    /** For an embedded struct field: the annotation of a pointer to that field. */
    public String ctypeOfField(Type t) {
        return annotation(unqualified(t) instanceof Declared d ? tag(d.tree()) : null);
    }

    String annotation(String tag) {
        return ctypeAnnotation == null || tag == null ? "" : "@" + ctypeAnnotation + "(\"" + tag + "\") ";
    }

    static String tag(Type t) {
        return t instanceof Declared d ? tag(d.tree()) : null;
    }

    static String tag(Scoped s) {
        var k = s.kind();
        return (k == Scoped.Kind.STRUCT || k == Scoped.Kind.UNION) && !s.name().isEmpty() ? k.name().toLowerCase() + " " + s.name() : null;
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
