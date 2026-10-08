package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;

/** Feeds every initializer of and assignment to a local into {@link CTypes}. */
final class CTypeScan extends TreePathScanner<Void, Void> {
    final CTypes types;

    CTypeScan(CTypes types) { this.types = types; }

    @Override public Void visitVariable(VariableTree node, Void v) {
        var el = types.trees.getElement(getCurrentPath());
        if (el != null && el.getKind() == ElementKind.LOCAL_VARIABLE && node.getInitializer() != null)
            types.assigned(el, types.of(getCurrentPath(), node.getInitializer()));
        return super.visitVariable(node, v);
    }

    @Override public Void visitAssignment(AssignmentTree node, Void v) {
        var el = types.trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        if (el != null && el.getKind() == ElementKind.LOCAL_VARIABLE)
            types.assigned(el, types.of(getCurrentPath(), node.getExpression()));
        return super.visitAssignment(node, v);
    }

    @Override public Void visitClass(ClassTree node, Void v) {
        return null; // a nested class's methods are learned when the checker reaches them
    }
}
