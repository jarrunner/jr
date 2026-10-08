package jarrunner.teavm_native_check;

import com.sun.source.util.*;
import javax.lang.model.util.*;

/** Checks each top-level class once javac has attributed it, so every expression has its type. */
final class AnalyzeListener implements TaskListener {
    final Trees trees;
    final Config cfg;
    final Elements elements;
    final Types types;

    AnalyzeListener(JavacTask task, Config cfg) {
        this.trees = Trees.instance(task);
        this.cfg = cfg;
        this.elements = task.getElements();
        this.types = task.getTypes();
    }

    @Override public void finished(TaskEvent e) {
        if (e.getKind() != TaskEvent.Kind.ANALYZE || e.getTypeElement() == null) return;
        if (e.getTypeElement().getNestingKind().isNested()) return;
        var path = trees.getPath(e.getTypeElement());
        if (path != null) new Checker(trees, cfg, elements, types).scan(path, null);
    }
}
