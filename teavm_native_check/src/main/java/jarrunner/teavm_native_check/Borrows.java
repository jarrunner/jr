package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;

/** Rule NC7, one method at a time. A pointer parameter is borrowed unless marked {@code @Escapes}: the method may use
 *  it, but not return it, store it, or hand it to an {@code @Escapes} parameter. What counts as the parameter: the
 *  parameter itself, anything derived from it by offset or slicing ({@code p.add(n)}, {@code buf.from(n)},
 *  {@code buf.ptr()}), and a local assigned from one of those. Also tracked: locals holding memory an allocator
 *  returned inside a memScoped block, which must not reach an {@code @Escapes} parameter either. */
final class Borrows {
    static final Set<String> DERIVE = Set.of("add", "align", "from", "slice", "ptr");

    final Trees trees;
    final Config cfg;
    final Facts facts;
    final Lexical lex;
    final Map<Element, Element> alias = new HashMap<>();
    final Set<Element> scoped = new HashSet<>();

    Borrows(Trees trees, Config cfg, Facts facts, Lexical lex) {
        this.trees = trees;
        this.cfg = cfg;
        this.facts = facts;
        this.lex = lex;
    }

    void learn(TreePath method) {
        new BorrowScan(this).scan(method, null);
    }

    /** The borrowed parameter e is, or derives from; null if none. */
    Element root(TreePath at, ExpressionTree e) {
        e = strip(e);
        return switch (e) {
            case IdentifierTree i -> switch (element(at, i)) {
                case VariableElement v when v.getKind() == ElementKind.PARAMETER && facts.isPointer(v.asType())
                        && !Facts.has(v, cfg.escapes) -> v;
                case Element v when v != null -> alias.get(v);
                case null, default -> null;
            };
            case MethodInvocationTree mi when derived(at, mi) -> root(at, ((MemberSelectTree) mi.getMethodSelect()).getExpression());
            case MethodInvocationTree mi -> returnedArg(at, mi).map(a -> root(at, a)).filter(Objects::nonNull).findFirst().orElse(null);
            case ConditionalExpressionTree c -> {
                var a = root(at, c.getTrueExpression());
                yield a != null ? a : root(at, c.getFalseExpression());
            }
            default -> null;
        };
    }

    /** True if e is memory an allocator returned, or derived from it, or a local allocated inside a scope. */
    boolean scopedAlloc(TreePath at, ExpressionTree e) {
        e = strip(e);
        return switch (e) {
            case IdentifierTree i -> scoped.contains(element(at, i));
            case MethodInvocationTree mi when derived(at, mi) ->
                    scopedAlloc(at, ((MemberSelectTree) mi.getMethodSelect()).getExpression());
            case MethodInvocationTree mi -> element(at, mi) instanceof ExecutableElement m && cfg.allocators.contains(Facts.qualified(m))
                    || returnedArg(at, mi).anyMatch(a -> scopedAlloc(at, a));
            default -> false;
        };
    }

    /** The arguments passed to {@code @Returned} parameters: the call's result may point into them. */
    private java.util.stream.Stream<ExpressionTree> returnedArg(TreePath at, MethodInvocationTree mi) {
        if (!(element(at, mi) instanceof ExecutableElement m)) return java.util.stream.Stream.empty();
        var ps = m.getParameters();
        var args = mi.getArguments();
        var out = new ArrayList<ExpressionTree>();
        for (var i = 0; i < ps.size() && i < args.size(); i++) if (Facts.has(ps.get(i), cfg.returned)) out.add(args.get(i));
        return out.stream();
    }

    void assigned(TreePath at, Element local, ExpressionTree value) {
        if (local == null) return;
        var r = root(at, value);
        if (r != null) alias.put(local, r);
        if (lex.inScope(at) && scopedAlloc(at, value)) scoped.add(local);
    }

    /** p.add(n), buf.from(n), buf.slice(..), buf.ptr(): a method of a pointer type that returns a pointer type. */
    private boolean derived(TreePath at, MethodInvocationTree mi) {
        return mi.getMethodSelect() instanceof MemberSelectTree s && DERIVE.contains(s.getIdentifier().toString())
                && facts.isPointer(trees.getTypeMirror(new TreePath(at, s.getExpression())))
                && facts.isPointer(trees.getTypeMirror(new TreePath(at, mi)));
    }

    private Element element(TreePath at, Tree t) {
        return trees.getElement(new TreePath(at, t));
    }

    static ExpressionTree strip(ExpressionTree e) {
        while (true) {
            if (e instanceof ParenthesizedTree p) e = p.getExpression();
            else if (e instanceof TypeCastTree c) e = c.getExpression();
            else return e;
        }
    }
}
