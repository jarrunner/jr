///usr/bin/env java "$0" "$@" ; exit $?
// Standalone Java code (not part of main project) - replaces bash/python/batch scripts with IDE-friendly, maintainable code using JDK 11/21/25 enhancements. To know why, refer to Cay Horstmann's JavaOne 2025 talk "Java for Small Coding Tasks" (https://youtu.be/04wFgshWMdA)

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Verifies WinAPI struct field offsets against the REAL compiler (llvm-mingw's clang, msvcrt
 * target - the same toolchain jr's TeaVM port compiles against, see
 * $claudeprompts/guidelines.teavmcpp.md) instead of hand-computing x64 alignment/padding, then
 * emits a small Java class of verified offset/size constants for TeaVM's Address-based marshaling.
 * See ../../prp/08-prp-teavm_struct_offset_generator.md.
 *
 * Add a struct: edit winstructs.toml (name + header + full field list), re-run. Nothing else
 * changes - this is the whole point, it turns "compute alignment by hand" into "add a name to
 * a list".
 *
 * Usage (from this directory): java OffsetGen.java [--spec winstructs.toml] [--out <path>]
 * [--package pocapp.jr] [--build build] [--clang <path-to-clang.exe>]
 */
public class OffsetGen {

    record StructSpec(String name, String header, List<String> fields) {}

    private static final Pattern KV = Pattern.compile("^(\\w+)\\s*=\\s*(.+)$");
    private static final Path FALLBACK_CLANG =
            Path.of("C:\\user\\Apps\\cmdtools\\llvm-mingw-msvcrt-x86_64\\bin\\x86_64-w64-mingw32-clang.exe");

    public static void main(String[] args) throws Exception {
        var spec = Path.of("winstructs.toml");
        var out = Path.of("..", "src", "main", "java", "pocapp", "jr", "WinOffsets.java");
        var buildDir = Path.of("build");
        var pkg = "pocapp.jr";
        Path clangArg = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--spec" -> spec = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--build" -> buildDir = Path.of(args[++i]);
                case "--package" -> pkg = args[++i];
                case "--clang" -> clangArg = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }

        var clang = resolveClang(clangArg);
        System.out.println("Using clang: " + clang);

        var structs = parseSpec(spec);
        System.out.println("Structs to verify: " + structs.stream().map(StructSpec::name).toList());

        var cSource = generateC(structs);
        var exe = compileProbe(cSource, clang, buildDir);
        var values = runProbe(exe);

        System.out.println();
        for (var s : structs) {
            System.out.println(s.name() + " SIZE=" + values.get(s.name() + ".SIZE"));
            for (var f : s.fields()) {
                System.out.println("  " + f + " @ " + values.get(s.name() + "." + f));
            }
        }

        var javaSource = generateJava(pkg, structs, values);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, javaSource);
        System.out.println();
        System.out.println("Wrote " + out.toAbsolutePath().normalize());
    }

    private static Path resolveClang(Path explicit) {
        if (explicit != null) {
            return explicit;
        }
        var envOverride = System.getenv("JR_CLANG");
        if (envOverride != null) {
            return Path.of(envOverride);
        }
        if (runsOk("x86_64-w64-mingw32-clang", "--version")) {
            return Path.of("x86_64-w64-mingw32-clang");
        }
        if (Files.isRegularFile(FALLBACK_CLANG)) {
            return FALLBACK_CLANG;
        }
        throw new RuntimeException("Could not find x86_64-w64-mingw32-clang on PATH or at "
                + FALLBACK_CLANG + " - pass --clang <path-to-clang.exe>");
    }

    private static boolean runsOk(String... command) {
        try {
            var p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Minimal parser for THIS file's exact shape ([[struct]] blocks with name/header/fields
     *  string-array keys) - not a general TOML parser. */
    private static List<StructSpec> parseSpec(Path specPath) throws IOException {
        var out = new ArrayList<StructSpec>();
        String name = null;
        String header = null;
        List<String> fields = null;
        for (var raw : Files.readAllLines(specPath)) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("[[struct]]")) {
                if (name != null) {
                    out.add(new StructSpec(name, header, fields));
                }
                name = null;
                header = "windows.h";
                fields = null;
                continue;
            }
            var m = KV.matcher(line);
            if (!m.matches()) {
                throw new IllegalArgumentException("Can't parse spec line in " + specPath + ": " + line);
            }
            switch (m.group(1)) {
                case "name" -> name = unquote(m.group(2));
                case "header" -> header = unquote(m.group(2));
                case "fields" -> fields = parseArray(m.group(2));
                default -> throw new IllegalArgumentException("Unknown key in " + specPath + ": " + line);
            }
        }
        if (name != null) {
            out.add(new StructSpec(name, header, fields));
        }
        return out;
    }

    private static String unquote(String s) {
        s = s.strip();
        return (s.startsWith("\"") && s.endsWith("\"")) ? s.substring(1, s.length() - 1) : s;
    }

    private static List<String> parseArray(String s) {
        s = s.strip();
        if (!s.startsWith("[") || !s.endsWith("]")) {
            throw new IllegalArgumentException("Expected a [\"...\", ...] array, got: " + s);
        }
        var inner = s.substring(1, s.length() - 1).strip();
        if (inner.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(inner.split(",")).map(String::strip).map(OffsetGen::unquote).toList();
    }

    private static String generateC(List<StructSpec> structs) {
        var headers = structs.stream().map(StructSpec::header).distinct().sorted().toList();
        var sb = new StringBuilder();
        sb.append("#include <stdio.h>\n#include <stddef.h>\n");
        for (var h : headers) {
            sb.append("#include <").append(h).append(">\n");
        }
        sb.append("\nint main(void) {\n");
        for (var s : structs) {
            sb.append("    printf(\"").append(s.name()).append(".SIZE=%lu\\n\", (unsigned long) sizeof(")
                    .append(s.name()).append("));\n");
            for (var f : s.fields()) {
                sb.append("    printf(\"").append(s.name()).append(".").append(f).append("=%lu\\n\", (unsigned long) offsetof(")
                        .append(s.name()).append(", ").append(f).append("));\n");
            }
        }
        sb.append("    return 0;\n}\n");
        return sb.toString();
    }

    private static Path compileProbe(String cSource, Path clang, Path buildDir) throws IOException, InterruptedException {
        Files.createDirectories(buildDir);
        var cFile = buildDir.resolve("probe.c");
        Files.writeString(cFile, cSource);
        var exeFile = buildDir.resolve("probe.exe");
        var pb = new ProcessBuilder(clang.toString(), "-O0", "-o", exeFile.toString(), cFile.toString());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var output = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) {
            throw new RuntimeException("clang failed compiling " + cFile + ":\n" + output);
        }
        return exeFile;
    }

    private static Map<String, Long> runProbe(Path exeFile) throws IOException, InterruptedException {
        var proc = new ProcessBuilder(exeFile.toAbsolutePath().toString()).redirectErrorStream(true).start();
        var output = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) {
            throw new RuntimeException(exeFile + " failed:\n" + output);
        }
        var result = new LinkedHashMap<String, Long>();
        for (var line : output.lines().toList()) {
            line = line.strip();
            var idx = line.indexOf('=');
            if (idx < 0) {
                continue;
            }
            result.put(line.substring(0, idx), Long.parseLong(line.substring(idx + 1)));
        }
        return result;
    }

    private static String generateJava(String packageName, List<StructSpec> structs, Map<String, Long> values) {
        var sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("/** GENERATED by teavm-poc/offsetgen/OffsetGen.java from winstructs.toml - do not hand-edit.\n")
          .append(" *  Offsets/sizes are verified against the real x86_64-w64-mingw32-clang (msvcrt) compiler's\n")
          .append(" *  own offsetof()/sizeof(), not hand-computed - see ../../prp/08-prp-teavm_struct_offset_generator.md.\n")
          .append(" *  Regenerate: cd teavm-poc/offsetgen && java OffsetGen.java */\n");
        sb.append("public final class WinOffsets {\n");
        sb.append("    private WinOffsets() {}\n\n");
        for (var s : structs) {
            sb.append("    public static final class ").append(s.name()).append(" {\n");
            sb.append("        private ").append(s.name()).append("() {}\n");
            sb.append("        public static final int SIZE = ").append(values.get(s.name() + ".SIZE")).append(";\n");
            for (var f : s.fields()) {
                sb.append("        public static final int ").append(f).append(" = ")
                        .append(values.get(s.name() + "." + f)).append(";\n");
            }
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
