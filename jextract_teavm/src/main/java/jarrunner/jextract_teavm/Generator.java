package jarrunner.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.Declaration.Constant;

/** Parse once, resolve every requested name against the real headers, emit what each one actually is. */
public final class Generator {
    /** {@code qsort(4)}: the function-pointer type of qsort's 4th parameter, for platforms that name no typedef for it. */
    static final Pattern PARAM = Pattern.compile("(\\w+)\\((\\d+)\\)");
    final ClangParse parser;
    final DataModel dm;
    final FunctionGen fn;
    final ConstantGen cg;
    final StructGen sg;
    final CallbackGen cb;
    final CVerifier verifier;
    final Src api = new Src(), structs = new Src();
    final List<String> problems = new ArrayList<>();
    int functions, constants, structCount, callbacks, macros;

    public Generator(ClangParse parser, String ctypeAnnotation) {
        this.parser = parser;
        this.dm = DataModel.of(parser.target);
        var types = new TypeMap(dm, ctypeAnnotation);
        this.fn = new FunctionGen(types);
        this.cg = new ConstantGen(types);
        this.sg = new StructGen(types);
        this.cb = new CallbackGen(types, fn);
        this.verifier = new CVerifier(types);
    }

    public void run(List<String> headers, List<Map.Entry<String, String>> symbols) {
        var table = new SymbolTable(parser.parse(headers));
        if (parser.errors > 0) throw new IllegalStateException("clang reported errors:\n" + parser.errorLines());
        var wide = symbols.stream().map(Map.Entry::getKey).distinct().filter(n -> table.find(n).map(this::isWide).orElse(false)).toList();
        var specs = new IdentityHashMap<Map.Entry<String, String>, MacroGen.Spec>();
        symbols.stream().filter(e -> MacroGen.is(e.getKey()))
                .forEach(e -> attempt(e.getKey(), () -> specs.put(e, MacroGen.parse(specs.size(), e.getKey(), e.getValue()))));
        var text = fn.converters.isEmpty() ? List.<TextParams.Param>of() : symbols.stream().map(Map.Entry::getKey).distinct()
                .flatMap(n -> table.find(n).stream()).filter(Declaration.Function.class::isInstance)
                .flatMap(d -> TextParams.of((Declaration.Function) d, fn.types).stream()).toList();
        var probe = probe(headers, Stream.of(WideStrings.probeLines(wide), MacroGen.probeLines(specs.values()), TextParams.probeLines(text))
                .flatMap(List::stream).toList());
        var decoded = WideStrings.read(probe, wide);
        fn.readOnly = TextParams.readOnly(probe, text);
        symbols.forEach(e -> attempt(e.getKey(), () -> {
            var c = e.getKey();
            var param = PARAM.matcher(c);
            if (MacroGen.is(c)) {
                if (specs.containsKey(e)) macro(specs.get(e), MacroGen.resolve(probe, specs.get(e)));
            } else if (param.matches()) paramCallback(table, param.group(1), Integer.parseInt(param.group(2)), e.getValue());
            else emit(table.find(c).orElseThrow(() -> new Unsupported("not declared in any parsed header")), e.getValue(), decoded.get(c));
        }));
    }

    void attempt(String cName, Runnable r) {
        try {
            r.run();
        } catch (Unsupported u) {
            problems.add(cName + ": " + u.getMessage());
        }
    }

    /** One extra parse for everything that needs clang to evaluate an expression the headers never spell out. */
    SymbolTable probe(List<String> headers, List<String> lines) {
        return new SymbolTable(parser.probe(lines.isEmpty() ? List.of() : headers, lines));
    }

    boolean isWide(Declaration d) {
        return d instanceof Constant c && c.value() instanceof String && TypeMap.isWideString(c.type(), dm);
    }

    void emit(Declaration d, String binding, String wide) {
        var all = List.of(binding.split(" "));
        var javaName = all.getFirst();
        var parts = all.stream().filter(p -> !p.startsWith("escapes=") && !p.startsWith("releases=") && !p.startsWith("fails=")).toList();
        var escaping = all.stream().filter(p -> p.startsWith("escapes=")).flatMap(p -> Arrays.stream(p.substring(8).split(",")))
                .map(Integer::parseInt).collect(java.util.stream.Collectors.toSet());
        var releases = all.stream().filter(p -> p.startsWith("releases=")).map(p -> p.substring(9)).findFirst().orElse(null);
        var fails = all.stream().filter(p -> p.startsWith("fails=")).map(p -> p.substring(6)).findFirst().orElse(null);
        switch (d) {
            case Declaration.Function f -> {
                fn.releases = releases;
                fn.fails = fails;
                api.addAll(fn.emit(f, javaName, parts.subList(1, parts.size()), escaping));
                fn.releases = null;
                fn.fails = null;
                verifier.function(f);
                functions++;
            }
            case Constant c -> {
                api.addAll(cg.emit(c, javaName, wide));
                verifier.constant(c, wide);
                constants++;
            }
            case Declaration.Typedef t when CallbackGen.functionOf(t.type()).isPresent() ->
                    callback(t.name(), CallbackGen.functionOf(t.type()).get(), javaName, t.pos(),
                    Attrs.declString(t).orElse(null));
            default -> {
                var s = SymbolTable.structOf(d).orElseThrow(() -> new Unsupported(
                        "is a " + d.getClass().getSimpleName().replace("Impl", "").toLowerCase() + " with nothing to bind"));
                structs.addAll(sg.emit(s, javaName));
                var cSpelling = d instanceof Declaration.Typedef ? d.name() : s.kind().name().toLowerCase() + " " + s.name();
                verifier.struct(s, cSpelling, Attrs.sizeBits(s) / 8, sg.fieldsOf(s));
                structCount++;
            }
        }
    }

    void paramCallback(SymbolTable table, String function, int index, String binding) {
        var f = table.find(function).filter(Declaration.Function.class::isInstance).map(Declaration.Function.class::cast)
                .orElseThrow(() -> new Unsupported(function + " is not a function in any parsed header"));
        if (index < 1 || index > f.parameters().size())
            throw new Unsupported(function + " has " + f.parameters().size() + " parameters, not a parameter " + index);
        var p = f.parameters().get(index - 1);
        var type = CallbackGen.functionOf(p.type()).orElseThrow(() -> new Unsupported(
                "parameter " + index + " of " + function + " is a " + TypeMap.cName(p.type()) + ", not a pointer to a function"));
        var javaName = binding.equals(function + "(" + index + ")") ? function + "_" + (p.name().isEmpty() ? "arg" + index : p.name()) : binding.split(" ")[0];
        callback(function + "(" + index + ")", type, javaName, f.pos(), null);
    }

    void callback(String cName, Type.Function f, String javaName, Position pos, String declText) {
        api.addAll(cb.emit(cName, f, javaName, pos, declText));
        verifier.callback(cName, f);
        callbacks++;
    }

    void macro(MacroGen.Spec s, MacroGen.Result r) {
        api.addAll(MacroGen.emit(s, r));
        verifier.macro(s, r);
        macros++;
    }

    public Collection<String> notes() {
        return sg.notes;
    }
}
