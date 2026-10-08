package jarrunner.teavm_native_check;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.tools.*;
import org.junit.jupiter.api.*;

/** Compiles each file in cases/ with the plugin, against stub sources, and compares the rule codes reported with the
 *  file's first line: {@code // expect: NC1-field NC2-ofData} (order does not matter) or {@code // expect: none}. */
class CasesTest {
    static final Path RES = Path.of("src/test/resources");
    static final String PLUGIN = "-Xplugin:NativeCheck scope=t.N.memScoped raw=t.N,t.Api,t.Buf alloc=t.N.alloc wrappers=t.Buf trust=t.Buf.wrap tracked=t.Fail takes=t.N.handOver";

    @TestFactory
    Stream<DynamicTest> cases() throws Exception {
        try (var files = Files.list(RES.resolve("cases"))) {
            return files.sorted().toList().stream()
                    .map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> check(f)));
        }
    }

    @Test
    void messageExplainsWhatWhyAndFix() throws Exception {
        var d = compile(RES.resolve("cases/raw.java"), PLUGIN).getFirst();
        var m = d.getMessage(Locale.ROOT);
        assertTrue(m.startsWith("[NC4-raw] Raw pointer access ('getInt')"), m);
        assertTrue(m.contains("Why: ") && m.contains("Fix: "), m);
    }

    @Test
    void warnModeReportsWithoutFailing() throws Exception {
        var ds = compile(RES.resolve("cases/raw.java"), PLUGIN + " mode=warn");
        assertFalse(ds.isEmpty());
        assertTrue(ds.stream().allMatch(d -> d.getKind() == Diagnostic.Kind.WARNING));
    }

    static void check(Path file) throws Exception {
        var first = Files.readAllLines(file).getFirst();
        assertTrue(first.startsWith("// expect: "), file + " must start with // expect:");
        var want = Arrays.stream(first.substring(11).trim().split("\\s+")).filter(s -> !s.equals("none")).sorted().toList();
        var ds = compile(file, PLUGIN);
        var got = ds.stream().map(d -> d.getMessage(Locale.ROOT)).filter(m -> m.startsWith("[NC"))
                .map(m -> m.substring(1, m.indexOf(']'))).sorted().toList();
        var other = ds.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR && !d.getMessage(Locale.ROOT).startsWith("[NC")).toList();
        assertTrue(other.isEmpty(), "case does not compile: " + other);
        assertEquals(want, got, () -> ds.stream().map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT).lines().findFirst().orElse(""))
                .collect(Collectors.joining("\n", "\n", "")));
    }

    static List<Diagnostic<? extends JavaFileObject>> compile(Path file, String plugin) throws Exception {
        var javac = ToolProvider.getSystemJavaCompiler();
        var diags = new DiagnosticCollector<JavaFileObject>();
        var out = Files.createTempDirectory("nc");
        try (var fm = javac.getStandardFileManager(diags, Locale.ROOT, null); var stubs = Files.walk(RES.resolve("stubs"))) {
            var sources = new ArrayList<>(stubs.filter(p -> p.toString().endsWith(".java")).toList());
            sources.add(file);
            var opts = List.of("-d", out.toString(), "-processorpath", System.getProperty("java.class.path"), "-Xlint:-options", plugin);
            javac.getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromPaths(sources)).call();
        }
        return diags.getDiagnostics();
    }
}
