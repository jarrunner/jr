import module java.base;
import module jdk.compiler;

import com.sun.source.tree.*;
import com.sun.source.util.*;

class Scan extends TreePathScanner<Void, Void> {
    final CompilationUnitTree cu;
    final SourcePositions pos;

    Scan(CompilationUnitTree cu, SourcePositions pos) { this.cu = cu; this.pos = pos; }

    String where(Tree t) {
        var line = cu.getLineMap().getLineNumber(pos.getStartPosition(cu, t));
        var file = Path.of(cu.getSourceFile().toUri()).getFileName();
        var src = t.toString().replaceAll("\\s+", " ");
        return file + ":" + line + "  " + (src.length() > 110 ? src.substring(0, 110) + "..." : src);
    }

    @Override public Void visitExpressionStatement(ExpressionStatementTree s, Void v) { r1(s); return super.visitExpressionStatement(s, v); }
    @Override public Void visitReturn(ReturnTree s, Void v) { r1(s); return super.visitReturn(s, v); }
    @Override public Void visitVariable(VariableTree s, Void v) { r1(s); return super.visitVariable(s, v); }
    @Override public Void visitIf(IfTree s, Void v) { r1(s.getCondition()); return super.visitIf(s, v); }

    void r1(Tree t) {
        for (var call : calls(t))
            if (call.getArguments().stream().filter(AddressLint::isAllocAddr).count() >= 2) {
                AddressLint.hits.add("R1 " + where(call));
                return;
            }
    }

    List<MethodInvocationTree> calls(Tree t) {
        var out = new ArrayList<MethodInvocationTree>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree m, Void v) { out.add(m); return super.visitMethodInvocation(m, v); }
            @Override public Void visitLambdaExpression(LambdaExpressionTree l, Void v) { return null; }
        }.scan(t, null);
        return out;
    }

    @Override public Void visitBlock(BlockTree b, Void v) {
        var st = b.getStatements();
        for (var i = 0; i < st.size(); i++)
            if (st.get(i) instanceof VariableTree vt && vt.getInitializer() instanceof MethodInvocationTree m
                    && AddressLint.name(m).equals("Address.ofData") && m.getArguments().getFirst() instanceof IdentifierTree arr)
                r2(st, i, vt.getName().toString(), arr.getName().toString());
        return super.visitBlock(b, v);
    }

    void r2(List<? extends StatementTree> st, int decl, String addr, String arr) {
        var last = -1;
        for (var j = decl + 1; j < st.size(); j++) if (AddressLint.refs(st.get(j), addr)) last = j;
        if (last < 0) return;
        for (var j = last; j < st.size(); j++) if (AddressLint.refs(st.get(j), arr)) return;
        for (var j = decl + 1; j <= last; j++)
            if (AddressLint.count(st.get(j), AddressLint::isGcPoint) > 0) {
                AddressLint.hits.add("R2 " + where(st.get(decl)) + "   <- GC point at line "
                        + cu.getLineMap().getLineNumber(pos.getStartPosition(cu, st.get(j))));
                return;
            }
    }
}
