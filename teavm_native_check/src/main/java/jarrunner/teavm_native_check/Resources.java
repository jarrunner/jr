package jarrunner.teavm_native_check;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;

/**
 * Rule NC8: an OS resource opened in a method is closed on every path out of it, or handed on. One method (or one
 * lambda that is not a scope) at a time, by walking its statements with the set of locals that may hold an open
 * resource; a scope lambda (memScoped) is walked in place, since it runs exactly once, right there.
 * <ul>
 * <li>Opened: the result of a call marked {@code @Acquires("release1,release2")}, the names of the calls that close it.
 * <li>Closed: passed to one of those calls. Handed on: returned (the method must then be {@code @Acquires} itself),
 *     stored in a field or array, or passed to a parameter marked {@code @Owns}. An {@code @Owns} parameter is open on
 *     entry, so the method that takes it is held to the same rule.
 * <li>Not opened: the branch on which the local was compared with {@code ==} (directly, or through a no-argument call on
 *     it such as {@code h.toLong() == 0}), or tested with {@code h.isNull()}: that is how a failed open is recognised.
 * <li>Every path: returns, the end of the method, and every exception path - a {@code throw}, or a call to a method that
 *     declares what it throws. A {@code finally} that closes it covers them all. Exceptions nobody declares (a bug: a
 *     null, an index) are not paths here, like a Rust panic that aborts.
 * </ul>
 * Also NC8-throws: a method that lets one of the {@code tracked} exceptions out declares it, so its callers see the
 * path. Opening through an out-parameter (CreateProcessW's PROCESS_INFORMATION) is not tracked.
 */
final class Resources {
    record Site(Tree at, String what, Set<String> releases) {}

    sealed interface Val {}
    record Fresh(Site site) implements Val {}
    record Var(Element v) implements Val {}

    /** A state: the locals that may hold an open resource, each with where it was opened. null = unreachable. */
    private static Map<Element, Site> copy(Map<Element, Site> s) { return s == null ? null : new LinkedHashMap<>(s); }

    private static Map<Element, Site> join(Map<Element, Site> a, Map<Element, Site> b) {
        if (a == null) return copy(b);
        if (b == null) return copy(a);
        var r = new LinkedHashMap<>(a);
        b.forEach(r::putIfAbsent);
        return r;
    }

    private static Map<Element, Site> without(Map<Element, Site> s, Element v) {
        if (s == null || !s.containsKey(v)) return s;
        var r = new LinkedHashMap<>(s);
        r.remove(v);
        return r;
    }

    /** Where control can go from inside the unit. */
    private sealed interface Frame {}
    private static final class Loop implements Frame {
        final Tree stmt; final String label; Map<Element, Site> breaks, continues;
        Loop(Tree stmt, String label) { this.stmt = stmt; this.label = label; }
    }
    private static final class Try implements Frame {
        final TryTree t; final Map<Tree, Map<Element, Site>> catches = new IdentityHashMap<>();
        Try(TryTree t) { this.t = t; }
    }
    private static final class Scope implements Frame {
        Map<Element, Site> returns; Site result;
    }

    final Checker c;
    final Types types;
    private TreePath unit;
    private boolean isMethod;
    private ExecutableElement method;
    /** The NC10 pass: the same walk, tracking values that may be a function's failure value (@Fails) until tested. */
    private boolean fail;
    private final Deque<Frame> frames = new ArrayDeque<>();
    /** Each finding once, though loops are walked until nothing changes. */
    private final Set<String> reported = new HashSet<>();

    Resources(Checker c, Types types) {
        this.c = c;
        this.types = types;
    }

    void onMethod(TreePath path, MethodTree m, ExecutableElement el) {
        if (m.getBody() == null) return;
        unit = path;
        isMethod = true;
        method = el;
        Map<Element, Site> s = new LinkedHashMap<>();
        for (var p : m.getParameters()) {
            var pe = c.trees.getElement(new TreePath(path, p));
            if (Facts.has(pe, c.cfg.owns)) s.put(pe, new Site(p, "the caller (@" + simple(c.cfg.owns) + ")", releases(pe, c.cfg.owns)));
        }
        fail = false;
        run(m.getBody(), s);
        fail = true;
        Map<Element, Site> n = new LinkedHashMap<>();
        for (var p : m.getParameters()) { // a @Nullable parameter may be NULL: this method is checked to handle it
            var pe = c.trees.getElement(new TreePath(path, p));
            if (Facts.has(pe, c.cfg.nullable)) n.put(pe, new Site(p, "the caller (@" + simple(c.cfg.nullable) + ")", Set.of("NULL")));
        }
        run(m.getBody(), n);
    }

    void onLambda(TreePath path, LambdaExpressionTree l) {
        unit = path;
        isMethod = false;
        method = null;
        for (var mode : new boolean[] {false, true}) {
            fail = mode;
            if (l.getBody() instanceof BlockTree b) run(b, new LinkedHashMap<>());
            else {
                var r = expr((ExpressionTree) l.getBody(), new LinkedHashMap<>());
                atReturn(l.getBody(), r.s, r.v);
            }
        }
    }

    private void run(BlockTree body, Map<Element, Site> entry) {
        frames.clear();
        var end = stmt(body, entry);
        if (end != null) for (var e : end.entrySet()) leak(e.getValue().at(), e.getKey(), e.getValue(), "the method ends");
    }

    // ---- statements -------------------------------------------------------------------------------------------

    private Map<Element, Site> stmt(StatementTree t, Map<Element, Site> s) {
        if (s == null || t == null) return s;
        return switch (t) {
            case BlockTree b -> {
                for (var st : b.getStatements()) s = stmt(st, s);
                yield s;
            }
            case ExpressionStatementTree e -> {
                var r = expr(e.getExpression(), s);
                if (r.v instanceof Fresh f) lost(e, f.site(), "its result is not kept");
                yield r.s;
            }
            case VariableTree v -> {
                if (v.getInitializer() == null) yield s;
                var r = expr(v.getInitializer(), s);
                yield assignLocal(v, c.trees.getElement(new TreePath(unit, v)), r);
            }
            case IfTree i -> {
                var b = cond(i.getCondition(), s);
                yield join(stmt(i.getThenStatement(), b[0]), i.getElseStatement() == null ? b[1] : stmt(i.getElseStatement(), b[1]));
            }
            case WhileLoopTree w -> loop(w, null, s, w.getCondition(), w.getStatement(), null);
            case DoWhileLoopTree d -> doLoop(d, null, s);
            case ForLoopTree f -> {
                for (var init : f.getInitializer()) s = stmt(init, s);
                yield loop(f, null, s, f.getCondition(), f.getStatement(), f.getUpdate());
            }
            case EnhancedForLoopTree f -> loop(f, null, expr(f.getExpression(), s).s, null, f.getStatement(), null);
            case LabeledStatementTree l -> labeled(l, s);
            case SwitchTree sw -> switchStmt(sw, null, s);
            case TryTree tr -> tryStmt(tr, s);
            case ReturnTree r -> {
                var res = r.getExpression() == null ? new R(s, null) : expr(r.getExpression(), s);
                ret(r, res);
                yield null;
            }
            case ThrowTree th -> {
                var r = expr(th.getExpression(), s);
                raise(th, r.s, List.of(c.trees.getTypeMirror(new TreePath(unit, th.getExpression()))), null);
                yield null;
            }
            case BreakTree b -> {
                jump(b, s, b.getLabel() == null ? null : b.getLabel().toString(), false);
                yield null;
            }
            case ContinueTree ct -> {
                jump(ct, s, ct.getLabel() == null ? null : ct.getLabel().toString(), true);
                yield null;
            }
            case YieldTree y -> {
                var r = expr(y.getValue(), s);
                jump(y, r.s, null, false);
                yield null;
            }
            case SynchronizedTree sy -> stmt(sy.getBlock(), expr(sy.getExpression(), s).s);
            case AssertTree a -> s;
            case ClassTree ct -> s; // a local class: its methods are units of their own
            default -> s;
        };
    }

    private Map<Element, Site> labeled(LabeledStatementTree l, Map<Element, Site> s) {
        var label = l.getLabel().toString();
        return switch (l.getStatement()) {
            case WhileLoopTree w -> loop(w, label, s, w.getCondition(), w.getStatement(), null);
            case DoWhileLoopTree d -> doLoop(d, label, s);
            case ForLoopTree f -> {
                for (var init : f.getInitializer()) s = stmt(init, s);
                yield loop(f, label, s, f.getCondition(), f.getStatement(), f.getUpdate());
            }
            case EnhancedForLoopTree f -> loop(f, label, expr(f.getExpression(), s).s, null, f.getStatement(), null);
            case SwitchTree sw -> switchStmt(sw, label, s);
            default -> {
                var frame = new Loop(l, label);
                frames.push(frame);
                var out = stmt(l.getStatement(), s);
                frames.pop();
                yield join(out, frame.breaks);
            }
        };
    }

    /** Walked until the entry state stops growing (it only grows, and is finite). */
    private Map<Element, Site> loop(Tree t, String label, Map<Element, Site> s, ExpressionTree cond,
            StatementTree body, List<? extends ExpressionStatementTree> update) {
        var entry = s;
        Map<Element, Site> exit = null;
        Loop frame = null;
        for (var round = 0; round < 50; round++) {
            var b = cond == null ? new Map[] {copy(entry), copy(entry)} : cond(cond, entry);
            frame = new Loop(t, label);
            frames.push(frame);
            var out = stmt(body, b[0]);
            frames.pop();
            out = join(out, frame.continues);
            if (update != null) for (var u : update) out = stmt(u, out);
            exit = b[1];
            var next = join(entry, out);
            if (next == null || next.equals(entry)) break;
            entry = next;
        }
        return join(exit, frame.breaks);
    }

    private Map<Element, Site> doLoop(DoWhileLoopTree d, String label, Map<Element, Site> s) {
        var entry = s;
        Map<Element, Site> exit = null;
        Loop frame = null;
        for (var round = 0; round < 50; round++) {
            frame = new Loop(d, label);
            frames.push(frame);
            var out = join(stmt(d.getStatement(), entry), null);
            frames.pop();
            out = join(out, frame.continues);
            var b = cond(d.getCondition(), out);
            exit = b[1];
            var next = join(entry, b[0]);
            if (next == null || next.equals(entry)) break;
            entry = next;
        }
        return join(exit, frame.breaks);
    }

    private Map<Element, Site> switchStmt(SwitchTree sw, String label, Map<Element, Site> s) {
        var r = expr(sw.getExpression(), s);
        var frame = new Loop(sw, label);
        frames.push(frame);
        var out = cases(sw.getCases(), r.s, frame);
        frames.pop();
        return join(out, frame.breaks);
    }

    /** The state after the last case falls out, plus the selector's state when no case is a default. */
    private Map<Element, Site> cases(List<? extends CaseTree> cases, Map<Element, Site> s, Loop frame) {
        Map<Element, Site> fall = null;
        var hasDefault = false;
        for (var k : cases) {
            if (k.getLabels().stream().anyMatch(l -> l instanceof DefaultCaseLabelTree)) hasDefault = true;
            var in = k.getCaseKind() == CaseTree.CaseKind.RULE ? copy(s) : join(s, fall);
            if (k.getGuard() != null) in = expr(k.getGuard(), in).s;
            Map<Element, Site> out;
            if (k.getCaseKind() == CaseTree.CaseKind.RULE) {
                out = switch (k.getBody()) {
                    case BlockTree b -> stmt(b, in);
                    case StatementTree st -> stmt(st, in);
                    case ExpressionTree e -> {
                        var r = expr(e, in);
                        frame.breaks = join(frame.breaks, r.s); // a value (switch expression) or a statement
                        yield null;
                    }
                    default -> in;
                };
                frame.breaks = join(frame.breaks, out);
                fall = null;
            } else {
                out = in;
                for (var st : k.getStatements()) out = stmt(st, out);
                fall = out;
            }
        }
        return hasDefault ? fall : join(fall, s);
    }

    private Map<Element, Site> tryStmt(TryTree t, Map<Element, Site> s) {
        var frame = new Try(t);
        frames.push(frame);
        for (var res : t.getResources()) s = res instanceof StatementTree st ? stmt(st, s) : expr((ExpressionTree) res, s).s;
        var out = stmt(t.getBlock(), s);
        frames.pop();
        // catches run with the try's own frame gone; a throw inside one leaves through the finally, which stays
        var withFinally = t.getFinallyBlock() != null ? new Try(t) : null;
        if (withFinally != null) frames.push(withFinally);
        for (var k : t.getCatches()) out = join(out, stmt(k.getBlock(), frame.catches.get(k)));
        if (withFinally != null) frames.pop();
        return t.getFinallyBlock() == null ? out : stmt(t.getFinallyBlock(), out);
    }

    // ---- leaving the normal flow -------------------------------------------------------------------------------

    private void ret(ReturnTree r, R res) {
        var s = res.s;
        var it = frames.iterator();
        var stack = new ArrayList<Frame>();
        while (it.hasNext()) stack.add(it.next());
        // through the finally blocks between here and the method, or up to the scope lambda being returned from
        for (var i = 0; i < stack.size(); i++) {
            var f = stack.get(i);
            if (f instanceof Scope sc) {
                var v = res.v;
                if (v instanceof Var var && s != null && s.containsKey(var.v())) {
                    sc.result = s.get(var.v());
                    s = without(s, var.v());
                } else if (v instanceof Fresh fr) sc.result = fr.site();
                sc.returns = join(sc.returns, s);
                return;
            }
            if (f instanceof Try tr && tr.t.getFinallyBlock() != null && isInBlockOrCatch(tr, r))
                s = finallyFrom(stack, i, tr.t.getFinallyBlock(), s);
        }
        atReturn(r, s, res.v);
    }

    private void atReturn(Tree at, Map<Element, Site> s, Val v) {
        if (s == null) return;
        if (fail) {
            var site = v instanceof Var var && s.containsKey(var.v()) ? s.get(var.v()) : v instanceof Fresh f ? f.site() : null;
            if (site != null && isMethod && Facts.value(method, c.cfg.fails) == null)
                report(Rule.FAIL_RETURN, at, failValue(site), site.what());
            return;
        }
        if (v instanceof Var var && s.containsKey(var.v())) {
            if (isMethod && !Facts.has(method, c.cfg.acquires)) report(Rule.ACQUIRES, at, s.get(var.v()).what());
            s = without(s, var.v());
        } else if (v instanceof Fresh f && isMethod && !Facts.has(method, c.cfg.acquires)) {
            report(Rule.ACQUIRES, at, f.site().what());
        }
        for (var e : s.entrySet()) leak(at, e.getKey(), e.getValue(), "the method returns here");
    }

    /** An exception of one of {@code thrown} leaves {@code at} (a throw, or the call named by {@code how}): to the catch
     *  that takes it, through finally blocks. */
    private void raise(Tree at, Map<Element, Site> s, List<? extends TypeMirror> thrown, String how) {
        if (s == null) return;
        var stack = new ArrayList<>(frames);
        for (var ex : thrown) {
            var st = s;
            var caught = false;
            for (var i = 0; i < stack.size() && !caught; i++) {
                if (!(stack.get(i) instanceof Try tr)) continue;
                if (isInBlock(tr, at)) {
                    for (var k : tr.t.getCatches()) {
                        if (catches(k, ex)) {
                            tr.catches.merge(k, copy(st), Resources::join);
                            if (definitelyCatches(k, ex)) { caught = true; break; }
                        }
                    }
                }
                if (!caught && tr.t.getFinallyBlock() != null) st = finallyFrom(stack, i, tr.t.getFinallyBlock(), st);
            }
            if (caught || st == null) continue;
            var name = ex.toString().substring(ex.toString().lastIndexOf('.') + 1);
            if (!fail && isMethod && isTracked(ex) && !declares(method, ex) && reported.add("throws" + ex + System.identityHashCode(method)))
                c.report(Rule.THROWS, at, name, method.getSimpleName());
            var when = how == null ? name + " is thrown here" : how + " can throw " + name + " here";
            for (var e : st.entrySet()) leak(at, e.getKey(), e.getValue(), when);
        }
    }

    /** The finally block run from inside the frames below index i (it sees only the frames outside its try). */
    private Map<Element, Site> finallyFrom(List<Frame> stack, int i, BlockTree fin, Map<Element, Site> s) {
        var saved = new ArrayDeque<>(frames);
        frames.clear();
        for (var j = stack.size() - 1; j > i; j--) frames.push(stack.get(j));
        var out = stmt(fin, s);
        frames.clear();
        for (var it = saved.descendingIterator(); it.hasNext(); ) frames.push(it.next());
        return out;
    }

    private void jump(Tree at, Map<Element, Site> s, String label, boolean isContinue) {
        var stack = new ArrayList<>(frames);
        for (var i = 0; i < stack.size(); i++) {
            var f = stack.get(i);
            if (f instanceof Scope) return; // a break cannot leave a lambda
            if (f instanceof Try tr && tr.t.getFinallyBlock() != null && isInBlockOrCatch(tr, at))
                s = finallyFrom(stack, i, tr.t.getFinallyBlock(), s);
            if (f instanceof Loop l && matches(l, label, isContinue, at)) {
                if (isContinue) l.continues = join(l.continues, s);
                else l.breaks = join(l.breaks, s);
                return;
            }
        }
    }

    private static boolean matches(Loop l, String label, boolean isContinue, Tree at) {
        if (label != null) return label.equals(l.label);
        if (at instanceof YieldTree) return l.stmt instanceof SwitchExpressionTree;
        if (isContinue) return !(l.stmt instanceof SwitchTree || l.stmt instanceof LabeledStatementTree);
        return !(l.stmt instanceof LabeledStatementTree) && !(l.stmt instanceof SwitchExpressionTree);
    }

    private boolean isInBlock(Try tr, Tree at) { return contains(tr.t.getBlock(), at) || tr.t.getResources().stream().anyMatch(r -> contains(r, at)); }

    private boolean isInBlockOrCatch(Try tr, Tree at) {
        return isInBlock(tr, at) || tr.t.getCatches().stream().anyMatch(k -> contains(k, at));
    }

    private static boolean contains(Tree outer, Tree inner) {
        var found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override public Void scan(Tree t, Void v) {
                if (t == inner) found[0] = true;
                return found[0] ? null : super.scan(t, v);
            }
        }.scan(outer, null);
        return found[0];
    }

    private boolean catches(CatchTree k, TypeMirror ex) {
        for (var t : catchTypes(k)) if (types.isAssignable(ex, t) || types.isAssignable(t, ex)) return true;
        return false;
    }

    private boolean definitelyCatches(CatchTree k, TypeMirror ex) {
        for (var t : catchTypes(k)) if (types.isAssignable(ex, t)) return true;
        return false;
    }

    private List<TypeMirror> catchTypes(CatchTree k) {
        var t = c.trees.getTypeMirror(new TreePath(unit, k.getParameter().getType()));
        return t instanceof UnionType u ? new ArrayList<>(u.getAlternatives()) : t == null ? List.of() : List.of(t);
    }

    private boolean isTracked(TypeMirror ex) {
        return ex instanceof DeclaredType d && c.cfg.tracked.contains(((TypeElement) d.asElement()).getQualifiedName().toString());
    }

    private boolean declares(ExecutableElement m, TypeMirror ex) {
        for (var t : m.getThrownTypes()) if (types.isAssignable(ex, t)) return true;
        return false;
    }

    // ---- expressions -------------------------------------------------------------------------------------------

    private record R(Map<Element, Site> s, Val v) {}

    private R expr(ExpressionTree e, Map<Element, Site> s) {
        if (s == null || e == null) return new R(s, null);
        return switch (e) {
            case ParenthesizedTree p -> expr(p.getExpression(), s);
            case TypeCastTree tc -> expr(tc.getExpression(), s);
            case IdentifierTree i -> {
                var el = c.trees.getElement(new TreePath(unit, i));
                yield new R(s, el != null && s.containsKey(el) ? new Var(el) : null);
            }
            case MethodInvocationTree mi -> call(mi, s);
            case NewClassTree n -> {
                if (n.getEnclosingExpression() != null) s = expr(n.getEnclosingExpression(), s).s;
                yield invoke(n, c.trees.getElement(new TreePath(unit, n)) instanceof ExecutableElement m ? m : null, n.getArguments(), s);
            }
            case AssignmentTree a -> {
                var target = Borrows.strip(a.getVariable());
                if (target instanceof ArrayAccessTree aa) s = expr(aa.getIndex(), expr(aa.getExpression(), s).s).s;
                else if (target instanceof MemberSelectTree ms) s = expr(ms.getExpression(), s).s;
                var r = expr(a.getExpression(), s);
                var el = c.trees.getElement(new TreePath(unit, target));
                if (target instanceof IdentifierTree && el instanceof VariableElement v && v.getKind() != ElementKind.FIELD)
                    yield new R(assignLocal(a, el, r), null);
                // a field or an array element: the resource is handed to whatever owns that
                yield new R(r.v instanceof Var var ? without(r.s, var.v()) : r.s, null);
            }
            case CompoundAssignmentTree ca -> new R(expr(ca.getExpression(), expr(ca.getVariable(), s).s).s, null);
            case ConditionalExpressionTree ce -> {
                var b = cond(ce.getCondition(), s);
                var t = expr(ce.getTrueExpression(), b[0]);
                var f = expr(ce.getFalseExpression(), b[1]);
                yield new R(join(t.s, f.s), t.v != null ? t.v : f.v);
            }
            case BinaryTree b when isLogical(b) -> {
                var br = cond(b, s);
                yield new R(join(br[0], br[1]), null);
            }
            case BinaryTree b -> new R(expr(b.getRightOperand(), expr(b.getLeftOperand(), s).s).s, null);
            case UnaryTree u -> new R(expr(u.getExpression(), s).s, null);
            case MemberSelectTree ms -> new R(expr(ms.getExpression(), s).s, null);
            case ArrayAccessTree aa -> new R(expr(aa.getIndex(), expr(aa.getExpression(), s).s).s, null);
            case NewArrayTree na -> {
                if (na.getDimensions() != null) for (var d : na.getDimensions()) s = expr(d, s).s;
                if (na.getInitializers() != null) for (var i : na.getInitializers()) {
                    var r = expr(i, s);
                    s = r.v instanceof Var var ? without(r.s, var.v()) : r.s;
                }
                yield new R(s, null);
            }
            case InstanceOfTree io -> new R(expr(io.getExpression(), s).s, null);
            case SwitchExpressionTree sw -> {
                var r = expr(sw.getExpression(), s);
                var frame = new Loop(sw, null);
                frames.push(frame);
                var out = cases(sw.getCases(), r.s, frame);
                frames.pop();
                yield new R(join(out, frame.breaks), null);
            }
            case LambdaExpressionTree l -> new R(s, null); // a unit of its own, unless a scope's (see call)
            default -> new R(s, null);
        };
    }

    private R call(MethodInvocationTree mi, Map<Element, Site> s) {
        if (mi.getMethodSelect() instanceof MemberSelectTree ms) s = expr(ms.getExpression(), s).s;
        var m = c.trees.getElement(new TreePath(unit, mi)) instanceof ExecutableElement x ? x : null;
        if (m != null && c.cfg.scope.contains(Facts.qualified(m))) {
            Site result = null;
            for (var a : mi.getArguments()) {
                if (Borrows.strip(a) instanceof LambdaExpressionTree l) {
                    var sc = new Scope();
                    frames.push(sc);
                    Map<Element, Site> out;
                    if (l.getBody() instanceof BlockTree b) out = stmt(b, s);
                    else {
                        var r = expr((ExpressionTree) l.getBody(), s);
                        out = null;
                        sc.returns = r.v instanceof Var var ? without(r.s, var.v()) : r.s;
                        sc.result = r.v instanceof Var var && r.s != null ? r.s.get(var.v()) : r.v instanceof Fresh f ? f.site() : null;
                    }
                    frames.pop();
                    s = join(out, sc.returns);
                    if (sc.result != null) result = sc.result;
                } else s = expr(a, s).s;
            }
            return new R(s, result != null ? new Fresh(result) : null);
        }
        return invoke(mi, m, mi.getArguments(), s);
    }

    private R invoke(Tree at, ExecutableElement m, List<? extends ExpressionTree> args, Map<Element, Site> s) {
        var vals = new ArrayList<Val>();
        for (var a : args) {
            var r = expr(a, s);
            s = r.s;
            vals.add(r.v);
        }
        var name = m == null ? "" : m.getSimpleName().toString();
        var qualified = m == null || !(m.getEnclosingElement() instanceof TypeElement) ? name : Facts.qualified(m);
        // a call that may throw: the exception leaves with everything still open before the call closes anything
        if (m != null && !m.getThrownTypes().isEmpty() && s != null) raise(at, s, m.getThrownTypes(), "'" + name + "'");
        var params = m == null ? List.<VariableElement>of() : m.getParameters();
        if (fail) return failCall(at, m, name, args, vals, params, s);
        for (var i = 0; i < vals.size(); i++) {
            var v = vals.get(i);
            var p = i < params.size() ? params.get(i) : params.isEmpty() ? null : params.getLast();
            var owns = p != null && Facts.has(p, c.cfg.owns) || c.cfg.takes.contains(qualified);
            if (v instanceof Var var && s != null && s.containsKey(var.v())) {
                var site = s.get(var.v());
                if (owns || closes(site, name, qualified)) s = without(s, var.v());
            } else if (v instanceof Fresh f && !owns && !closes(f.site(), name, qualified)) {
                lost(args.get(i), f.site(), "it is passed straight to '" + name + "', which does not close it");
            }
        }
        if (m != null && Facts.has(m, c.cfg.acquires)) {
            var rel = releases(m, c.cfg.acquires);
            if (!rel.isEmpty()) return new R(s, new Fresh(new Site(at, "'" + name + "'", rel)));
        }
        return new R(s, null);
    }

    /** NC10: a value that may be the failure value reaches only @Nullable parameters until it is tested. */
    private R failCall(Tree at, ExecutableElement m, String name, List<? extends ExpressionTree> args, List<Val> vals,
            List<? extends VariableElement> params, Map<Element, Site> s) {
        for (var i = 0; i < vals.size(); i++) {
            var p = i < params.size() ? params.get(i) : params.isEmpty() ? null : params.getLast();
            if (p != null && Facts.has(p, c.cfg.nullable)) continue;
            if (vals.get(i) instanceof Var var && s != null && s.containsKey(var.v())) {
                var site = s.get(var.v());
                report(Rule.UNCHECKED, args.get(i), var.v().getSimpleName(), site.what(), line(site.at()), failValue(site), name);
                s = without(s, var.v()); // once is enough
            } else if (vals.get(i) instanceof Fresh f) {
                report(Rule.UNCHECKED, args.get(i), "the result", f.site().what(), line(f.site().at()), failValue(f.site()), name);
            }
        }
        var fv = m == null ? null : Facts.value(m, c.cfg.fails);
        return new R(s, fv == null ? null : new Fresh(new Site(at, "'" + name + "'", Set.of(fv))));
    }

    private static String failValue(Site site) { return site.releases().iterator().next(); }

    /** An operand naming the value a function fails with: 0 or NULL for "NULL", -1 or the constant itself for any other. */
    private static boolean isFailure(ExpressionTree e, String value) {
        e = Borrows.strip(e);
        if (e instanceof MethodInvocationTree mi && mi.getArguments().isEmpty() && mi.getMethodSelect() instanceof MemberSelectTree ms)
            e = Borrows.strip(ms.getExpression()); // INVALID_HANDLE_VALUE.toLong()
        if (e instanceof TypeCastTree tc) e = Borrows.strip(tc.getExpression());
        if (e instanceof LiteralTree l && l.getValue() instanceof Number n)
            return value.equals("NULL") ? n.longValue() == 0 : n.longValue() == -1;
        if (e instanceof UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS && u.getExpression() instanceof LiteralTree l
                && l.getValue() instanceof Number n) return !value.equals("NULL") && n.longValue() == 1;
        var n = e instanceof IdentifierTree i ? i.getName().toString() : e instanceof MemberSelectTree s ? s.getIdentifier().toString() : null;
        return n != null && n.equals(value);
    }

    private static boolean closes(Site site, String name, String qualified) {
        return site.releases().contains(name) || site.releases().contains(qualified);
    }

    /** {true branch, false branch}. */
    @SuppressWarnings("unchecked")
    private Map<Element, Site>[] cond(ExpressionTree e, Map<Element, Site> s) {
        if (s == null) return new Map[] {null, null};
        switch (e) {
            case ParenthesizedTree p -> { return cond(p.getExpression(), s); }
            case LiteralTree l when Boolean.TRUE.equals(l.getValue()) -> { return new Map[] {s, null}; }
            case LiteralTree l when Boolean.FALSE.equals(l.getValue()) -> { return new Map[] {null, s}; }
            case UnaryTree u when u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT -> {
                var b = cond(u.getExpression(), s);
                return new Map[] {b[1], b[0]};
            }
            case BinaryTree b when b.getKind() == Tree.Kind.CONDITIONAL_AND -> {
                var l = cond(b.getLeftOperand(), s);
                var r = cond(b.getRightOperand(), l[0]);
                return new Map[] {r[0], join(l[1], r[1])};
            }
            case BinaryTree b when b.getKind() == Tree.Kind.CONDITIONAL_OR -> {
                var l = cond(b.getLeftOperand(), s);
                var r = cond(b.getRightOperand(), l[1]);
                return new Map[] {join(l[0], r[0]), r[1]};
            }
            case BinaryTree b when b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO -> {
                var after = expr(b.getRightOperand(), expr(b.getLeftOperand(), s).s).s;
                var v = tested(b.getLeftOperand(), after);
                if (v == null) v = tested(b.getRightOperand(), after);
                if (v == null) return new Map[] {after, copy(after)};
                if (fail) {
                    var other = tested(b.getLeftOperand(), after) == v ? b.getRightOperand() : b.getLeftOperand();
                    if (!isFailure(other, failValue(after.get(v)))) return new Map[] {after, copy(after)}; // the wrong value: still unchecked
                    var checked = without(after, v);
                    return new Map[] {checked, copy(checked)};
                }
                var failed = without(after, v);
                return b.getKind() == Tree.Kind.EQUAL_TO ? new Map[] {failed, after} : new Map[] {after, failed};
            }
            case MethodInvocationTree mi when mi.getArguments().isEmpty() && mi.getMethodSelect() instanceof MemberSelectTree ms
                    && ms.getIdentifier().contentEquals("isNull") && tested(ms.getExpression(), s) != null -> {
                if (fail && !failValue(s.get(tested(ms.getExpression(), s))).equals("NULL")) return new Map[] {s, copy(s)};
                if (fail) { var checked = without(s, tested(ms.getExpression(), s)); return new Map[] {checked, copy(checked)}; }
                return new Map[] {without(s, tested(ms.getExpression(), s)), s};
            }
            default -> {
                var r = expr(e, s);
                return new Map[] {r.s, copy(r.s)};
            }
        }
    }

    /** The open local an equality test is about: the local itself, or a no-argument call on it (h.toLong()). */
    private Element tested(ExpressionTree e, Map<Element, Site> s) {
        e = Borrows.strip(e);
        if (e instanceof MethodInvocationTree mi && mi.getArguments().isEmpty() && mi.getMethodSelect() instanceof MemberSelectTree ms)
            e = Borrows.strip(ms.getExpression());
        if (!(e instanceof IdentifierTree)) return null;
        var el = c.trees.getElement(new TreePath(unit, e));
        return el != null && s != null && s.containsKey(el) ? el : null;
    }

    private static boolean isLogical(BinaryTree b) {
        return b.getKind() == Tree.Kind.CONDITIONAL_AND || b.getKind() == Tree.Kind.CONDITIONAL_OR;
    }

    private Map<Element, Site> assignLocal(Tree at, Element target, R r) {
        var s = r.s;
        if (s == null) return null;
        Site site = null;
        if (r.v instanceof Fresh f) site = f.site();
        else if (r.v instanceof Var var && !var.v().equals(target)) {
            site = s.get(var.v());
            s = without(s, var.v()); // moved: the new name now carries it
        } else if (r.v instanceof Var) return s;
        if (s.containsKey(target)) {
            var old = s.get(target);
            if (!fail) report(Rule.OVERWRITE, at, target.getSimpleName(), old.what(), line(old.at()), names(old));
            s = without(s, target);
        }
        if (site != null) {
            s = copy(s);
            s.put(target, site);
        }
        return s;
    }

    // ---- reporting ---------------------------------------------------------------------------------------------

    private void leak(Tree at, Element v, Site site, String when) {
        if (fail) return;
        if (reported.add("leak" + System.identityHashCode(at) + "/" + v))
            report(Rule.LEAK, at, v.getSimpleName(), site.what(), line(site.at()), when, names(site));
    }

    private void lost(Tree at, Site site, String how) {
        if (fail) return;
        if (reported.add("lost" + System.identityHashCode(at))) report(Rule.LOST, at, site.what(), how, names(site));
    }

    private void report(Rule r, Tree at, Object... args) {
        if (r == Rule.LEAK || r == Rule.LOST || reported.add(r + "" + System.identityHashCode(at))) c.report(r, at, args);
    }

    private long line(Tree t) {
        var cu = unit.getCompilationUnit();
        return cu.getLineMap().getLineNumber(c.trees.getSourcePositions().getStartPosition(cu, t));
    }

    private static String names(Site s) { return String.join(" or ", s.releases()); }

    private static String simple(String annotation) { return annotation.substring(annotation.lastIndexOf('.') + 1); }

    private static Set<String> releases(Element e, String annotation) {
        var v = Facts.value(e, annotation);
        var r = new LinkedHashSet<String>();
        if (v != null) for (var p : v.split(",")) if (!p.isBlank()) r.add(p.trim());
        return r;
    }
}
