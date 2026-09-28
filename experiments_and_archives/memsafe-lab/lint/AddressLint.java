import module java.base;
import module jdk.compiler;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.ToolProvider;

public class AddressLint {
    static final Set<String> ALLOC_ADDR = Set.of("Cstr.of", "Wstr.of");
    static final Set<String> NO_GC_OWNERS = Set.of("WinApi", "WinOffsets", "Address", "Math");
    static final Set<String> ADDRESS_OPS = Set.of("add", "toLong", "toInt", "getAddress", "putAddress", "getByte", "putByte",
            "getShort", "putShort", "getChar", "putChar", "getInt", "putInt", "getLong", "putLong", "invoke");
    static final List<String> hits = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        var root = Path.of(args.length > 0 ? args[0] : ".");
        var files = new ArrayList<File>();
        try (var s = Files.walk(root)) { s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile())); }
        var javac = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, null, d -> {}, List.of("-proc:none"), null,
                ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null).getJavaFileObjectsFromFiles(files));
        var positions = Trees.instance(javac).getSourcePositions();
        for (var cu : javac.parse()) new Scan(cu, positions).scan(cu, null);
        hits.forEach(System.out::println);
        var r1 = hits.stream().filter(h -> h.contains("R1")).count();
        System.out.println("R1 (two+ converted strings alive in one expression): " + r1);
        System.out.println("R2 (Address of a dead array, then a GC point before its last use): " + (hits.size() - r1));
    }

    static String name(ExpressionTree t) {
        return switch (t) {
            case MethodInvocationTree m -> name(m.getMethodSelect());
            case MemberSelectTree s -> (s.getExpression() instanceof IdentifierTree i ? i.getName() + "." : "") + s.getIdentifier();
            case IdentifierTree i -> i.getName().toString();
            default -> "";
        };
    }

    static String owner(MethodInvocationTree m) {
        var n = name(m);
        var dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(0, dot).replaceAll(".*\\.", "");
    }

    static boolean isAllocAddr(Tree t) {
        return t instanceof MethodInvocationTree m && ALLOC_ADDR.contains(name(m));
    }

    static boolean isGcPoint(Tree t) {
        return switch (t) {
            case NewArrayTree n -> true;
            case NewClassTree n -> true;
            case BinaryTree b when b.getKind() == Tree.Kind.PLUS && (isStringy(b.getLeftOperand()) || isStringy(b.getRightOperand())) -> true;
            case MethodInvocationTree m -> !NO_GC_OWNERS.contains(owner(m)) && !name(m).equals("Address.ofData")
                    && !(m.getMethodSelect() instanceof MemberSelectTree s && ADDRESS_OPS.contains(s.getIdentifier().toString()));
            default -> false;
        };
    }

    static boolean isStringy(ExpressionTree e) {
        return e instanceof LiteralTree l && l.getValue() instanceof String;
    }

    static int count(Tree t, Predicate<Tree> p) {
        var n = new int[1];
        new TreeScanner<Void, Void>() {
            @Override public Void scan(Tree tree, Void v) {
                if (tree != null && p.test(tree)) n[0]++;
                return super.scan(tree, v);
            }
        }.scan(t, null);
        return n[0];
    }

    static boolean refs(Tree t, String var) {
        return count(t, x -> x instanceof IdentifierTree i && i.getName().contentEquals(var)) > 0;
    }
}
