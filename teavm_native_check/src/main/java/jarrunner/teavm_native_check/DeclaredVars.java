package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;

/** Every variable declared within a tree, lambda parameters included. */
final class DeclaredVars extends TreePathScanner<Void, Set<Element>> {
    final Trees trees;

    private DeclaredVars(Trees trees) { this.trees = trees; }

    static Set<Element> of(Trees trees, TreePath root) {
        var found = new HashSet<Element>();
        new DeclaredVars(trees).scan(root, found);
        return found;
    }

    @Override public Void visitVariable(VariableTree node, Set<Element> found) {
        found.add(trees.getElement(getCurrentPath()));
        return super.visitVariable(node, found);
    }
}
