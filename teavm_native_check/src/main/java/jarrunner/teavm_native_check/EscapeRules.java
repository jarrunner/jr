package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.util.*;

/** Rule NC7 (borrowed pointer parameters, see {@link Borrows}) and the NC4 check on making pointers from numbers or
 *  objects. Called by {@link Checker} with the path of the tree being visited. */
final class EscapeRules {
    final Checker c;
    final Borrows borrows;
    final Elements elements;
    final Types types;

    EscapeRules(Checker c, Borrows borrows, Elements elements, Types types) {
        this.c = c;
        this.borrows = borrows;
        this.elements = elements;
        this.types = types;
    }

    void onReturn(TreePath path, ReturnTree node) {
        if (node.getExpression() == null || c.lex.returnTarget(path) != null) return; // a lambda's return: NC1 covers scopes
        var p = borrows.root(path, node.getExpression());
        if (p != null && !Facts.has(p, c.cfg.returned)) c.report(Rule.BORROW_RETURN, node, p.getSimpleName());
    }

    void onAssign(TreePath path, AssignmentTree node) {
        var target = Borrows.strip(node.getVariable());
        var stored = target instanceof ArrayAccessTree a ? a.getExpression() : target;
        if (!(c.trees.getElement(new TreePath(path, Borrows.strip(stored))) instanceof VariableElement v)) return;
        if (v.getKind() != ElementKind.FIELD && !(target instanceof ArrayAccessTree)) return; // a local: an alias, tracked
        if (c.facts.inWrapper(v)) return; // a wrapper adopting its pointer; the wrapper itself is then the borrowed value
        var p = borrows.root(path, node.getExpression());
        if (p != null) c.report(Rule.BORROW_STORE, node, p.getSimpleName(), v.getSimpleName());
    }

    /** Arguments reaching an {@code @Escapes} parameter of m (a method or a constructor). */
    void onCall(TreePath path, ExecutableElement m, List<? extends ExpressionTree> args) {
        var params = m.getParameters();
        for (var i = 0; i < params.size() && i < args.size(); i++) {
            if (!Facts.has(params.get(i), c.cfg.escapes)) continue;
            var arg = args.get(i);
            var p = borrows.root(path, arg);
            var callee = Facts.qualified(m);
            if (p != null) c.report(Rule.BORROW_PASS, arg, p.getSimpleName(), callee);
            else if (c.lex.inScope(path) && borrows.scopedAlloc(path, arg)) c.report(Rule.SCOPED_ESCAPE, arg, callee);
        }
    }

    /** An override may drop @Escapes from a parameter, never add it. */
    void onMethod(MethodTree node, ExecutableElement m) {
        var owner = (TypeElement) m.getEnclosingElement();
        for (var i = 0; i < m.getParameters().size(); i++) {
            if (!Facts.has(m.getParameters().get(i), c.cfg.escapes)) continue;
            for (var o : overridden(m, owner))
                if (!Facts.has(o.getParameters().get(i), c.cfg.escapes))
                    c.report(Rule.OVERRIDE, node.getParameters().get(i), m.getParameters().get(i).getSimpleName());
        }
    }

    /** NC4-forge: Address.fromLong/fromInt, outside a constant static final field (NC1 already checks those). */
    void onFromNumber(TreePath path, MethodInvocationTree node, String name) {
        if (!c.lex.rawAllowed(path) && !inStaticFinalInit(path)) c.report(Rule.FORGE, node, "a number (" + name + ")");
    }

    /** NC4-forge: a cast between a pointer type and anything else (the way a GetProcAddress result becomes a Function). */
    void onCast(TreePath path, TypeCastTree node) {
        var to = c.trees.getTypeMirror(new TreePath(path, node.getType()));
        var from = c.trees.getTypeMirror(new TreePath(path, node.getExpression()));
        if (from == null || from.getKind() == javax.lang.model.type.TypeKind.NULL) return;
        if (c.facts.isPointer(to) != c.facts.isPointer(from) && !c.lex.rawAllowed(path))
            c.report(Rule.FORGE, node, c.facts.isPointer(to) ? "an object (a cast)" : "an object, cast from a pointer");
    }

    private boolean inStaticFinalInit(TreePath p) {
        for (; p != null; p = p.getParentPath()) {
            var leaf = p.getLeaf();
            if (leaf instanceof VariableTree) {
                var mods = c.trees.getElement(p).getModifiers();
                return c.trees.getElement(p).getKind() == ElementKind.FIELD && mods.contains(Modifier.STATIC) && mods.contains(Modifier.FINAL);
            }
            if (leaf instanceof MethodTree || leaf instanceof ClassTree || leaf instanceof LambdaExpressionTree) return false;
        }
        return false;
    }

    private List<ExecutableElement> overridden(ExecutableElement m, TypeElement owner) {
        var out = new ArrayList<ExecutableElement>();
        var seen = new HashSet<Element>();
        var todo = new ArrayDeque<javax.lang.model.type.TypeMirror>(types.directSupertypes(owner.asType()));
        while (!todo.isEmpty()) {
            if (!(types.asElement(todo.pop()) instanceof TypeElement t) || !seen.add(t)) continue;
            for (var e : ElementFilter.methodsIn(t.getEnclosedElements()))
                if (elements.overrides(m, e, owner)) out.add(e);
            todo.addAll(types.directSupertypes(t.asType()));
        }
        return out;
    }
}
