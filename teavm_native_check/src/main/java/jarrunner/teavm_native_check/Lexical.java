package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;

/** Where a tree sits: inside which scope lambda, method or class. Answers walk up the path, so a lambda nested in a
 *  scope lambda (a forEach body, say) counts as inside the scope, while a method or class boundary ends it. */
final class Lexical {
    final Trees trees;
    final Config cfg;
    final Set<Tree> scopeLambdas = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<Tree, Set<Element>> declared = new IdentityHashMap<>();

    Lexical(Trees trees, Config cfg) {
        this.trees = trees;
        this.cfg = cfg;
    }

    TreePath scopeLambda(TreePath p) {
        for (; p != null; p = p.getParentPath()) {
            var leaf = p.getLeaf();
            if (isScopeLambda(p)) return p;
            if (leaf instanceof MethodTree || leaf instanceof ClassTree) return null;
        }
        return null;
    }

    boolean inScope(TreePath p) { return scopeLambda(p) != null; }

    /** In a scope, or in a method marked @Scoped, which hands the obligation to its callers. */
    boolean scopeCovered(TreePath p) {
        for (; p != null; p = p.getParentPath()) {
            var leaf = p.getLeaf();
            if (isScopeLambda(p)) return true;
            if (leaf instanceof MethodTree) return Facts.has(trees.getElement(p), cfg.scoped);
            if (leaf instanceof ClassTree) return false;
        }
        return false;
    }

    /** In a class listed as raw, or in any method or class marked @Unsafe. */
    boolean rawAllowed(TreePath p) {
        for (; p != null; p = p.getParentPath()) {
            var leaf = p.getLeaf();
            if (!(leaf instanceof MethodTree || leaf instanceof ClassTree)) continue;
            var el = trees.getElement(p);
            if (Facts.has(el, cfg.unsafe)) return true;
            if (el instanceof TypeElement te && cfg.raw.contains(te.getQualifiedName().toString())) return true;
        }
        return false;
    }

    /** The lambda a return statement returns from, or null if it returns from a method. */
    TreePath returnTarget(TreePath p) {
        for (; p != null; p = p.getParentPath()) {
            var leaf = p.getLeaf();
            if (leaf instanceof LambdaExpressionTree) return p;
            if (leaf instanceof MethodTree || leaf instanceof ClassTree) return null;
        }
        return null;
    }

    /** A lambda passed straight to a scope method (memScoped). Decided from the tree, not from what has been visited,
     *  so it holds before the checker reaches the call (Borrows learns a method up front). */
    boolean isScopeLambda(TreePath p) {
        if (p == null || !(p.getLeaf() instanceof LambdaExpressionTree)) return false;
        if (scopeLambdas.contains(p.getLeaf())) return true;
        var parent = p.getParentPath();
        while (parent != null && parent.getLeaf() instanceof ParenthesizedTree) parent = parent.getParentPath();
        var yes = parent != null && parent.getLeaf() instanceof MethodInvocationTree
                && trees.getElement(parent) instanceof ExecutableElement m && cfg.scope.contains(Facts.qualified(m));
        if (yes) scopeLambdas.add(p.getLeaf());
        return yes;
    }

    boolean declaredIn(TreePath lambda, Element e) {
        return declared.computeIfAbsent(lambda.getLeaf(), k -> DeclaredVars.of(trees, lambda)).contains(e);
    }
}
