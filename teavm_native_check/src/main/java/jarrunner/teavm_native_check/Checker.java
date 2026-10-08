package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.Diagnostic;

/** One pass over one top-level class. Each rule looks at a single method body; nothing is tracked across calls. */
final class Checker extends TreePathScanner<Void, Void> {
    static final Set<String> RAW_OPS = Set.of("add", "getByte", "putByte", "getChar", "putChar", "getShort", "putShort",
            "getInt", "putInt", "getLong", "putLong", "getFloat", "putFloat", "getDouble", "putDouble", "getAddress",
            "putAddress", "toStructure", "align", "fillZero", "fill", "moveMemoryBlock");

    final Trees trees;
    final Config cfg;
    final Facts facts;
    final Lexical lex;
    final CTypes ctypes;
    final Borrows borrows;
    final EscapeRules escapes;
    final Resources resources;

    Checker(Trees trees, Config cfg, Elements elements, Types types) {
        this.trees = trees;
        this.cfg = cfg;
        this.facts = new Facts(cfg);
        this.lex = new Lexical(trees, cfg);
        this.ctypes = new CTypes(trees, cfg);
        this.borrows = new Borrows(trees, cfg, facts, lex);
        this.escapes = new EscapeRules(this, borrows, elements, types);
        this.resources = new Resources(this, types);
    }

    @Override public Void visitClass(ClassTree node, Void v) {
        checkReason(trees.getElement(getCurrentPath()));
        return super.visitClass(node, v);
    }

    @Override public Void visitMethod(MethodTree node, Void v) {
        var el = trees.getElement(getCurrentPath());
        checkReason(el);
        ctypes.learn(getCurrentPath());
        borrows.learn(getCurrentPath());
        if (el instanceof ExecutableElement m) {
            escapes.onMethod(node, m);
            resources.onMethod(getCurrentPath(), node, m);
        }
        return super.visitMethod(node, v);
    }

    @Override public Void visitNewClass(NewClassTree node, Void v) {
        if (facts.hasPointerTypeArg(typeOf(node))) report(Rule.GENERIC, node, node.getIdentifier());
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement m) escapes.onCall(getCurrentPath(), m, node.getArguments());
        return super.visitNewClass(node, v);
    }

    @Override public Void visitTypeCast(TypeCastTree node, Void v) {
        escapes.onCast(getCurrentPath(), node);
        return super.visitTypeCast(node, v);
    }

    @Override public Void visitVariable(VariableTree node, Void v) {
        var el = trees.getElement(getCurrentPath());
        if (el != null) {
            var t = el.asType();
            if (facts.isPointerArray(t)) report(Rule.ARRAY, node, el.getSimpleName());
            else if (facts.hasPointerTypeArg(t) && (node.getInitializer() == null || !facts.hasPointerTypeArg(typeOf(node.getInitializer()))))
                report(Rule.GENERIC, node, el.getSimpleName());
            else if (el.getKind() == ElementKind.FIELD && facts.isPointer(t) && !Facts.has(el, cfg.handle) && !facts.inWrapper(el)
                    && !isConstantPointer(el, node.getInitializer()))
                report(Rule.FIELD, node, el.getSimpleName());
        }
        return super.visitVariable(node, v);
    }

    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void v) {
        if (facts.hasPointerTypeArg(typeOf(node))) report(Rule.GENERIC, node, node.getMethodSelect());
        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement m) {
            var q = Facts.qualified(m);
            if (cfg.scope.contains(q))
                for (var a : node.getArguments()) if (strip(a) instanceof LambdaExpressionTree l) lex.scopeLambdas.add(l);
            if (facts.isPointerType(m.getEnclosingElement())) checkPointerOp(node, m.getSimpleName().toString());
            if (cfg.trust.contains(q) && !lex.rawAllowed(getCurrentPath())) report(Rule.RAW, node, q);
            checkTypes(node, m);
            escapes.onCall(getCurrentPath(), m, node.getArguments());
            if (Facts.has(m, cfg.scoped) && !lex.scopeCovered(getCurrentPath())) report(Rule.SCOPED, node, q);
            if (cfg.callbackFactory.contains(q)) checkCallback(node);
            if ((cfg.scope.contains(q) || cfg.suspend.contains(q) || Facts.has(m, cfg.async)) && inForeign(getCurrentPath()))
                report(Rule.FOREIGN, node, q);
            if ((cfg.suspend.contains(q) || Facts.has(m, cfg.async)) && lex.inScope(getCurrentPath()))
                report(Rule.SUSPEND, node, q);
        }
        return super.visitMethodInvocation(node, v);
    }

    @Override public Void visitSynchronized(SynchronizedTree node, Void v) {
        if (lex.inScope(getCurrentPath())) report(Rule.SUSPEND, node, "synchronized");
        if (inForeign(getCurrentPath())) report(Rule.FOREIGN, node, "synchronized");
        return super.visitSynchronized(node, v);
    }

    @Override public Void visitLambdaExpression(LambdaExpressionTree node, Void v) {
        if (!lex.isScopeLambda(getCurrentPath())) resources.onLambda(getCurrentPath(), node); // a scope lambda is walked with its method
        if (lex.scopeLambdas.contains(node) && node.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION
                && facts.isPointer(typeOf(node.getBody())))
            report(Rule.RETURN, node.getBody());
        return super.visitLambdaExpression(node, v);
    }

    @Override public Void visitReturn(ReturnTree node, Void v) {
        if (node.getExpression() != null && lex.isScopeLambda(lex.returnTarget(getCurrentPath()))
                && facts.isPointer(typeOf(node.getExpression())))
            report(Rule.RETURN, node);
        escapes.onReturn(getCurrentPath(), node);
        return super.visitReturn(node, v);
    }

    @Override public Void visitAssignment(AssignmentTree node, Void v) {
        var target = strip(node.getVariable());
        var scope = lex.scopeLambda(getCurrentPath());
        if (scope != null && facts.isPointer(typeOf(target))) {
            if (target instanceof ArrayAccessTree) {
                var root = target;
                while (strip(root) instanceof ArrayAccessTree a) root = a.getExpression();
                var rootEl = elementOf(strip(root));
                if (rootEl != null && (rootEl.getKind() == ElementKind.FIELD || !lex.declaredIn(scope, rootEl)))
                    report(Rule.ASSIGN, node, rootEl.getSimpleName());
            } else if (elementOf(target) instanceof VariableElement f && f.getKind() == ElementKind.FIELD
                    && !Facts.has(f, cfg.handle)) {
                report(Rule.ASSIGN, node, f.getSimpleName());
            }
        }
        escapes.onAssign(getCurrentPath(), node);
        return super.visitAssignment(node, v);
    }

    private void checkPointerOp(MethodInvocationTree node, String name) {
        switch (name) {
            case "ofData" -> report(Rule.OF_DATA, node);
            case "ofObject" -> report(Rule.OF_OBJECT, node);
            case "fromLong", "fromInt" -> escapes.onFromNumber(getCurrentPath(), node, name);
            default -> {
                if (RAW_OPS.contains(name) && !lex.rawAllowed(getCurrentPath())) report(Rule.RAW, node, name);
            }
        }
    }

    /** NC9: {@code Function.get(Fn.class, Owner.class, "method")} names a Java method C will call; it must say which thread. */
    private void checkCallback(MethodInvocationTree node) {
        var args = node.getArguments();
        if (args.size() < 3 || !(strip(args.get(2)) instanceof LiteralTree lit) || !(lit.getValue() instanceof String name)) return;
        if (!(typeOf(args.get(1)) instanceof DeclaredType cls) || cls.getTypeArguments().size() != 1
                || !(cls.getTypeArguments().getFirst() instanceof DeclaredType owner)) return;
        for (var e : owner.asElement().getEnclosedElements())
            if (e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals(name)
                    && (Facts.value(e, cfg.foreign) != null || Facts.value(e, cfg.same) != null)) return;
        report(Rule.CALLBACK, node, name);
    }

    /** Inside a method marked as running on another OS thread (NC9). */
    private boolean inForeign(TreePath p) {
        for (; p != null; p = p.getParentPath())
            if (p.getLeaf() instanceof MethodTree) return Facts.has(trees.getElement(p), cfg.foreign);
        return false;
    }

    /** NC6: each argument whose struct is known against the parameter's {@code @CType}. */
    private void checkTypes(MethodInvocationTree node, ExecutableElement m) {
        var params = m.getParameters();
        var args = node.getArguments();
        for (var i = 0; i < params.size() && i < args.size(); i++) {
            var want = Facts.value(params.get(i), cfg.ctype);
            var got = want == null ? null : ctypes.of(getCurrentPath(), args.get(i));
            if (got != null && !got.equals(want)) report(Rule.TYPE, args.get(i), got, want);
        }
    }

    private void checkReason(Element el) {
        if (Facts.has(el, cfg.unsafe) && Facts.value(el, cfg.unsafe) == null) {
            var at = trees.getTree(el);
            report(Rule.REASON, at != null ? at : getCurrentPath().getLeaf(), el.getSimpleName());
        }
    }

    /** {@code static final} and initialised from a fixed number ({@code Address.fromInt(0)}) or from another such
     *  constant: it never points into memory a scope owns. */
    private boolean isConstantPointer(Element el, ExpressionTree init) {
        var mods = el.getModifiers();
        if (init == null || !mods.contains(Modifier.STATIC) || !mods.contains(Modifier.FINAL)) return false;
        return switch (strip(init)) {
            case MethodInvocationTree mi when elementOf(mi) instanceof ExecutableElement m
                    && facts.isPointerType(m.getEnclosingElement())
                    && (m.getSimpleName().contentEquals("fromInt") || m.getSimpleName().contentEquals("fromLong")) ->
                    mi.getArguments().stream().allMatch(a -> Facts.isConstant(a, this::elementOf));
            case IdentifierTree i when elementOf(i) instanceof VariableElement f -> isFixedField(f);
            case MemberSelectTree s when elementOf(s) instanceof VariableElement f -> isFixedField(f);
            default -> false;
        };
    }

    private boolean isFixedField(VariableElement f) {
        var mods = f.getModifiers();
        return f.getKind() == ElementKind.FIELD && mods.contains(Modifier.STATIC) && mods.contains(Modifier.FINAL);
    }

    private Element elementOf(Tree t) { return trees.getElement(new TreePath(getCurrentPath(), t)); }

    private TypeMirror typeOf(Tree t) { return trees.getTypeMirror(new TreePath(getCurrentPath(), t)); }

    private static ExpressionTree strip(ExpressionTree t) {
        while (t instanceof ParenthesizedTree p) t = p.getExpression();
        return t;
    }

    void report(Rule r, Tree at, Object... args) {
        trees.printMessage(cfg.warn ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR, r.message(args), at,
                getCurrentPath().getCompilationUnit());
    }
}
