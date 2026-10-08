package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;

/** Which C struct a pointer expression points to, from {@code @CType} on what produced it (rule NC6). Within one
 *  method: a local takes the type of everything assigned to it, and becomes unknown if those disagree or any is
 *  unknown. Unknown is never reported, so untyped code stays legal and migration can be gradual. */
final class CTypes {
    static final String MIXED = "";

    final Trees trees;
    final Config cfg;
    final Map<Element, String> locals = new HashMap<>();

    CTypes(Trees trees, Config cfg) {
        this.trees = trees;
        this.cfg = cfg;
    }

    /** Learns the locals of the method (lambdas included) at this path, in source order. */
    void learn(TreePath method) {
        new CTypeScan(this).scan(method, null);
    }

    void assigned(Element local, String type) {
        if (local == null) return;
        var t = type == null ? MIXED : type;
        locals.merge(local, t, (a, b) -> a.equals(b) ? a : MIXED);
    }

    /** The struct tag e points to, or null if not known. */
    String of(TreePath at, ExpressionTree e) {
        while (e instanceof ParenthesizedTree p) e = p.getExpression();
        return switch (e) {
            case ConditionalExpressionTree c -> {
                var a = of(at, c.getTrueExpression());
                yield a != null && a.equals(of(at, c.getFalseExpression())) ? a : null;
            }
            case MethodInvocationTree mi when element(at, mi) instanceof ExecutableElement m -> invoked(at, mi, m);
            case IdentifierTree i -> switch (element(at, i)) {
                case VariableElement v when v.getKind() == ElementKind.PARAMETER && Facts.value(v, cfg.ctype) != null ->
                        Facts.value(v, cfg.ctype);
                case VariableElement v -> {
                    var t = locals.get(v);
                    yield t == null || t.equals(MIXED) ? null : t;
                }
                case null, default -> null;
            };
            default -> null;
        };
    }

    private String invoked(TreePath at, MethodInvocationTree mi, ExecutableElement m) {
        var declared = Facts.value(m, cfg.ctype);
        if (declared != null) return declared;
        if (!cfg.alloc.contains(Facts.qualified(m)) || mi.getArguments().size() != 1) return null;
        return sizeOf(at, mi.getArguments().getFirst(), true);
    }

    /** {@code X.SIZE} of a struct class X, or a constant initialised from one ({@code SIZE_ALIAS = X.SIZE}). */
    private String sizeOf(TreePath at, ExpressionTree arg, boolean followAlias) {
        if (arg instanceof MemberSelectTree s && s.getIdentifier().contentEquals("SIZE")
                && element(at, s.getExpression()) instanceof TypeElement struct)
            return Facts.value(struct, cfg.ctype);
        if (followAlias && element(at, arg) instanceof VariableElement f && f.getKind() == ElementKind.FIELD
                && trees.getTree(f) instanceof VariableTree vt && vt.getInitializer() != null)
            return sizeOf(trees.getPath(f), vt.getInitializer(), false);
        return null;
    }

    private Element element(TreePath at, Tree t) {
        return trees.getElement(new TreePath(at, t));
    }
}
