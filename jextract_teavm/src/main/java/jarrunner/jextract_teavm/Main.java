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
    @Option(names = "--verify-c", description = "also write a C file of _Static_asserts restating every size, offset, width and value, for an independent compiler to check") Path verifyC;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var parser = new ClangParse(target, includes, defines, clangArgs);
        var gen = new Generator(parser);
        var t0 = System.nanoTime();
        try {
            gen.run(headers, Symbols.read(symbols));
        } finally {
            if (diagnostics != null) Files.writeString(diagnostics, parser.diagnostics);
        }
        var provenance = "from %s, target %s (%s). Symbols: %s".formatted(String.join(" ", headers), target, gen.dm,
                symbols.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(" + ")));
        if (!gen.api.isEmpty()) System.err.println("wrote " + JavaFile.write(out, pkg, apiClass, provenance, gen.api, gen.callbacks > 0 ? List.of("Address", "Function", "Import") : List.of("Address", "Import")));
        if (!gen.structs.isEmpty()) System.err.println("wrote " + JavaFile.write(out, pkg, structsClass, provenance, gen.structs, List.of("Address")));
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
