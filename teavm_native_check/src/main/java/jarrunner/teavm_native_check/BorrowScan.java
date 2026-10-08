package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;

/** Feeds every local initializer and assignment of a method into {@link Borrows}, in source order. */
final class BorrowScan extends TreePathScanner<Void, Void> {
    final Borrows b;

    BorrowScan(Borrows b) { this.b = b; }

    @Override public Void visitVariable(VariableTree node, Void v) {
        var el = b.trees.getElement(getCurrentPath());
        if (el != null && el.getKind() == ElementKind.LOCAL_VARIABLE && node.getInitializer() != null)
            b.assigned(getCurrentPath(), el, node.getInitializer());
        return super.visitVariable(node, v);
    }

    @Override public Void visitAssignment(AssignmentTree node, Void v) {
        var el = b.trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        if (el != null && el.getKind() == ElementKind.LOCAL_VARIABLE) b.assigned(getCurrentPath(), el, node.getExpression());
        return super.visitAssignment(node, v);
    }

    @Override public Void visitClass(ClassTree node, Void v) {
        return null; // a nested class's methods are learned when the checker reaches them
    }
}
