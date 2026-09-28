package littlejlib.jextract_teavm;

import module java.base;
import org.openjdk.jextract.*;
import org.openjdk.jextract.impl.*;

/** Runs jextract's own front end (libclang) with the include setup its CLI applies, plus a target triple. */
public final class ClangParse {
    final String target;
    final List<String> includes, defines, extra;
    String diagnostics = "";
    int errors, warnings;

    public ClangParse(String target, List<String> includes, List<String> defines, List<String> extra) {
        this.target = target;
        this.includes = includes;
        this.defines = defines;
        this.extra = extra;
    }

    public List<String> args() {
        var a = new ArrayList<String>();
        a.add("--target=" + target);
        defines.forEach(d -> a.add("-D" + d));
        includes.forEach(i -> a.add("-I" + i));
        var builtin = Path.of(System.getProperty("java.home"), "conf", "jextract");
        if (Files.isDirectory(builtin)) a.add("-I" + builtin);
        a.addAll(extra);
        return a;
    }

    /** A header is a path, or a system header spelled {@code <windows.h>}. Records clang's diagnostics. */
    public Declaration.Scoped parse(List<String> headers) {
        var err = new StringWriter();
        var top = parse(headers, List.of(), err);
        diagnostics = err.toString();
        errors = (int) diagnostics.lines().filter(l -> l.contains(": error:") || l.startsWith("error:")).count();
        warnings = (int) diagnostics.lines().filter(l -> l.contains("warning:")).count();
        return top;
    }

    /** The same headers plus {@code extraLines}, for expressions only clang can evaluate; its diagnostics are dropped. */
    public Declaration.Scoped probe(List<String> headers, List<String> extraLines) {
        return parse(headers, extraLines, new StringWriter());
    }

    Declaration.Scoped parse(List<String> headers, List<String> extraLines, StringWriter err) {
        var src = new ArrayList<String>();
        headers.forEach(h -> src.add(h.startsWith("<") ? "#include " + h : "#include \"" + Path.of(h).toAbsolutePath() + "\""));
        src.addAll(extraLines);
        var logger = new Logger(new PrintWriter(new StringWriter()), new PrintWriter(err, true));
        return new Parser(logger).parse("jextract_teavm$tmp.h", String.join("\n", src) + "\n", args());
    }

    public String errorLines() {
        return String.join("\n", diagnostics.lines().filter(l -> l.contains("error:")).limit(20).toList());
    }
}
