package jarrunner.jextract_teavm;

import module java.base;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "jextract-teavm", mixinStandardHelpOptions = true, version = "jextract-teavm 1.0",
        description = {"TeaVM @Import bindings from real C headers, using jextract's own libclang front end.",
                "Every signature, width, struct offset and constant value comes from clang's parse of the headers;",
                "nothing is typed by hand. Kinds are inferred: list names, get whatever each name actually is."})
public class Main implements Callable<Integer> {
    @Parameters(paramLabel = "HEADER", arity = "1..*", description = "header files, or system headers as \"<windows.h>\"")
    List<String> headers;
    @Option(names = "--symbols", required = true, description = "file: one C name per line, optional Java name after it; repeat to add a platform's own lines")
    List<Path> symbols;
    @Option(names = "-I", description = "include directory") List<String> includes = new ArrayList<>();
    @Option(names = "-D", description = "preprocessor define") List<String> defines = new ArrayList<>();
    @Option(names = "--clang-arg", description = "any further clang argument") List<String> clangArgs = new ArrayList<>();
    @Option(names = "--target", defaultValue = "x86_64-w64-mingw32", description = "clang target triple (default ${DEFAULT-VALUE})")
    String target;
    @Option(names = "--package", required = true) String pkg;
    @Option(names = "--api-class", defaultValue = "Bindings", description = "class for functions + constants") String apiClass;
    @Option(names = "--structs-class", defaultValue = "Structs", description = "class for struct layouts") String structsClass;
    @Option(names = {"-o", "--out"}, required = true, description = "source root to write into") Path out;
    @Option(names = "--diagnostics", description = "write clang's full warning/error output here") Path diagnostics;
    @Option(names = "--ctype-annotation", paramLabel = "NAME", description = "annotate every pointer to a named struct or union with @NAME(\"struct tag\") - parameters, returns, struct classes and accessors - for a compile-time checker (the annotation type is the caller's)") String ctypeAnnotation;
    @Option(names = "--text-converter", paramLabel = "TYPE=FUNCTION", description = "with --text-scope: for each function taking read-only text (const char* or const wchar_t*, as clang confirms), also generate an overload taking String; FUNCTION turns a String into native text, per TYPE char or wchar_t, e.g. wchar_t=N.wcstr") Map<String, String> textConverters = new LinkedHashMap<>();
    @Option(names = "--text-scope", paramLabel = "ENTER,EXIT", description = "the scope the String overloads convert inside: ENTER() returns a long mark, EXIT(mark) frees everything allocated since, e.g. N.mark,N.release") String textScope;
    @Option(names = "--buf-class", paramLabel = "NAME", description = "also generate struct accessors taking NAME, a bounds-checked buffer type with getX(int)/putX(int, x) and from(int)") String bufClass;
    @Option(names = "--returned-annotation", paramLabel = "NAME", description = "write @NAME on the struct parameter of each field-address accessor: its result points into that parameter (teavm-native-check NC7)") String returnedAnnotation;
    @Option(names = "--escapes-annotation", paramLabel = "NAME", description = "write @NAME on the parameters a symbols line marks escapes=N (1-based): the C function keeps that pointer after it returns, which no header says") String escapesAnnotation;
    @Option(names = "--acquires-annotation", paramLabel = "NAME", description = "write @NAME(\"close1,close2\") on a function whose symbols line says releases=close1,close2 (the Java names of the calls that close what it returns), and on its String overload (teavm-native-check NC8)") String acquiresAnnotation;
    @Option(names = "--fails-annotation", paramLabel = "NAME", description = "write @NAME(\"VALUE\") on a function whose symbols line says fails=VALUE (NULL, or a constant such as INVALID_HANDLE_VALUE): the value it returns on failure, which callers must test before using the result (teavm-native-check NC10)") String failsAnnotation;
    @Option(names = "--include", paramLabel = "HEADER", description = "write @Include(HEADER) on the api class, so TeaVM puts #include \"HEADER\" (or <HEADER>, written \"<HEADER>\") at the top of every C file that calls one of its functions; give the C compiler -I for its folder. One header: TeaVM takes one @Include per class, so name a header that includes the rest. Pick a name no Java class shares: on a case-insensitive filesystem \"jr.h\" finds TeaVM's own Jr.h") String include;
    @Option(names = "--verify-c", description = "also write a C file of _Static_asserts restating every size, offset, width and value, for an independent compiler to check") Path verifyC;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var parser = new ClangParse(target, includes, defines, clangArgs);
        var gen = new Generator(parser, ctypeAnnotation);
        gen.sg.bufClass = bufClass;
        gen.sg.returnedAnnotation = returnedAnnotation;
        gen.fn.escapesAnnotation = escapesAnnotation;
        gen.fn.acquiresAnnotation = acquiresAnnotation;
        gen.fn.failsAnnotation = failsAnnotation;
        if (!textConverters.isEmpty()) {
            var scope = textScope == null ? new String[0] : textScope.split(",");
            if (scope.length != 2) throw new ParameterException(new CommandLine(this), "--text-converter needs --text-scope ENTER,EXIT");
            gen.fn.converters = textConverters;
            gen.fn.scopeEnter = scope[0].trim();
            gen.fn.scopeExit = scope[1].trim();
        }
        var t0 = System.nanoTime();
        try {
            gen.run(headers, Symbols.read(symbols));
        } finally {
            if (diagnostics != null) Files.writeString(diagnostics, parser.diagnostics);
        }
        var provenance = "from %s, target %s (%s). Symbols: %s".formatted(String.join(" ", headers), target, gen.dm,
                symbols.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(" + ")));
        if (!gen.api.isEmpty()) System.err.println("wrote " + JavaFile.write(out, pkg, apiClass, provenance, gen.api, gen.callbacks > 0 ? List.of("Address", "Function", "Import") : List.of("Address", "Import"), include));
        if (!gen.structs.isEmpty()) System.err.println("wrote " + JavaFile.write(out, pkg, structsClass, provenance, gen.structs, List.of("Address"), null));
        System.err.printf("%d functions, %d constants, %d structs, %d callback types, %d macros in %d ms (clang: %d warnings, %d errors)%n",
                gen.functions, gen.constants, gen.structCount, gen.callbacks, gen.macros, (System.nanoTime() - t0) / 1_000_000, parser.warnings, parser.errors);
        if (verifyC != null) {
            gen.verifier.write(verifyC, headers);
            System.err.println("wrote " + verifyC + " (" + gen.verifier.asserts + " assertions)");
        }
        gen.notes().forEach(n -> System.err.println("note: " + n));
        gen.problems.forEach(p -> System.err.println("NOT BOUND: " + p));
        return gen.problems.isEmpty() ? 0 : 1;
    }
}
