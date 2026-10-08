package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Questions about types, elements and constant expressions, answered from javac's attributed model. */
final class Facts {
    final Config cfg;

    Facts(Config cfg) { this.cfg = cfg; }

    /** The raw pointer type or a wrapper of it. */
    boolean isPointer(TypeMirror t) {
        if (t == null || t.getKind() != TypeKind.DECLARED) return false;
        var name = ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().toString();
        return name.equals(cfg.pointer) || cfg.wrappers.contains(name);
    }

    /** A field declared in a wrapper class: how the wrapper holds its pointer. */
    boolean inWrapper(Element field) {
        return field.getEnclosingElement() instanceof TypeElement te && cfg.wrappers.contains(te.getQualifiedName().toString());
    }

    boolean isPointerArray(TypeMirror t) {
        while (t != null && t.getKind() == TypeKind.ARRAY) {
            t = ((ArrayType) t).getComponentType();
            if (isPointer(t)) return true;
        }
        return false;
    }

    boolean isPointerType(Element owner) {
        return owner instanceof TypeElement te && te.getQualifiedName().contentEquals(cfg.pointer);
    }

    static boolean has(Element e, String name) {
        if (e == null) return false;
        for (var am : e.getAnnotationMirrors()) {
            var te = (TypeElement) am.getAnnotationType().asElement();
            var n = name.indexOf('.') >= 0 ? te.getQualifiedName() : te.getSimpleName();
            if (n.contentEquals(name)) return true;
        }
        return false;
    }

    /** The annotation's {@code value} if it is a non-blank String, else null; also null if the annotation is absent. */
    static String value(Element e, String name) {
        for (var am : e.getAnnotationMirrors()) {
            var te = (TypeElement) am.getAnnotationType().asElement();
            var n = name.indexOf('.') >= 0 ? te.getQualifiedName() : te.getSimpleName();
            if (!n.contentEquals(name)) continue;
            for (var v : am.getElementValues().entrySet())
                if (v.getKey().getSimpleName().contentEquals("value") && v.getValue().getValue() instanceof String s && !s.isBlank())
                    return s;
        }
        return null;
    }

    /** {@code pkg.Class.method} of an invoked method. */
    static String qualified(ExecutableElement m) {
        return ((TypeElement) m.getEnclosingElement()).getQualifiedName() + "." + m.getSimpleName();
    }

    /** Literal numbers and compile-time constants, possibly cast or negated: a fixed pointer value such as NULL. */
    static boolean isConstant(ExpressionTree t, java.util.function.Function<Tree, Element> elementOf) {
        return switch (t) {
            case LiteralTree l -> true;
            case ParenthesizedTree p -> isConstant(p.getExpression(), elementOf);
            case TypeCastTree c -> isConstant(c.getExpression(), elementOf);
            case UnaryTree u -> isConstant(u.getExpression(), elementOf);
            case BinaryTree b -> isConstant(b.getLeftOperand(), elementOf) && isConstant(b.getRightOperand(), elementOf);
            case IdentifierTree i -> elementOf.apply(i) instanceof VariableElement v && v.getConstantValue() != null;
            case MemberSelectTree m -> elementOf.apply(m) instanceof VariableElement v && v.getConstantValue() != null;
            default -> false;
        };
    }
}
