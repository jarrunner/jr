package wintype;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles and runs a tiny throwaway C program against the REAL llvm-mingw clang to answer a
 * question about a real header - same discipline as offsetgen/OffsetGen.java (PRP-08) and the
 * BCRYPT_HASH_LENGTH probe (PRP-09), generalized into a reusable classifier. Deliberately
 * duplicated here rather than shared with OffsetGen.java - this is a standalone POC, and each
 * probe tool is small enough that sharing a library is not worth the coupling yet (see PRP-11's
 * "specificity before abstraction" note).
 */
public final class HeaderProbe {
    private HeaderProbe() {}

    public enum Kind { POINTER, INTEGER, UNKNOWN }

    public record TypeClassification(Kind kind, long sizeBytes) {}

    private static final Path FALLBACK_CLANG =
            Path.of("C:\\user\\Apps\\cmdtools\\llvm-mingw-msvcrt-x86_64\\bin\\x86_64-w64-mingw32-clang.exe");

    /** Classifies a real C type/typedef as pointer-shaped or integer-shaped, and its size, by
     *  asking the real compiler via __builtin_classify_type - not by pattern-matching the name. */
    public static TypeClassification classify(String cTypeName, String header) throws IOException, InterruptedException {
        var c = """
                #include <stdio.h>
                #include <windows.h>
                #include <%s>
                int main(void) {
                    printf("REF_PTR=%%d\\n", __builtin_classify_type((void*)0));
                    printf("REF_INT=%%d\\n", __builtin_classify_type((long long)0));
                    printf("SIZE=%%zu\\n", sizeof(%s));
                    printf("CLASS=%%d\\n", __builtin_classify_type((%s)0));
                    return 0;
                }
                """.formatted(header, cTypeName, cTypeName);
        var values = compileAndRun(c, "classify_" + cTypeName);
        var kind = Kind.UNKNOWN;
        if (values.get("CLASS").equals(values.get("REF_PTR"))) {
            kind = Kind.POINTER;
        } else if (values.get("CLASS").equals(values.get("REF_INT"))) {
            kind = Kind.INTEGER;
        }
        return new TypeClassification(kind, values.get("SIZE"));
    }

    /** Returns the REAL runtime value of a string macro constant (e.g. BCRYPT_HASH_LENGTH),
     *  by asking the compiler to print it - never hand-typed, so a wrong guess like
     *  "HashLength" (PRP-09's actual bug) can never be written down in the first place. */
    public static String constantValue(String macroName, String header) throws IOException, InterruptedException {
        var c = """
                #include <stdio.h>
                #include <wchar.h>
                #include <windows.h>
                #include <%s>
                int main(void) {
                    wprintf(L"VALUE=%%ls\\n", %s);
                    return 0;
                }
                """.formatted(header, macroName);
        var buildDir = Path.of("build");
        Files.createDirectories(buildDir);
        var cFile = buildDir.resolve("const_" + macroName + ".c");
        Files.writeString(cFile, c);
        var exe = compile(cFile, buildDir);
        var output = run(exe);
        for (var line : output.lines().toList()) {
            if (line.startsWith("VALUE=")) {
                return line.substring("VALUE=".length());
            }
        }
        throw new RuntimeException("No VALUE= line in output for " + macroName + ":\n" + output);
    }

    private static Map<String, Long> compileAndRun(String cSource, String label) throws IOException, InterruptedException {
        var buildDir = Path.of("build");
        Files.createDirectories(buildDir);
        var cFile = buildDir.resolve(label + ".c");
        Files.writeString(cFile, cSource);
        var exe = compile(cFile, buildDir);
        var output = run(exe);
        var result = new LinkedHashMap<String, Long>();
        for (var line : output.lines().toList()) {
            var idx = line.indexOf('=');
            if (idx < 0) {
                continue;
            }
            result.put(line.substring(0, idx), Long.parseLong(line.substring(idx + 1).strip()));
        }
        return result;
    }

    private static Path compile(Path cFile, Path buildDir) throws IOException, InterruptedException {
        var exeFile = buildDir.resolve(cFile.getFileName().toString().replace(".c", ".exe"));
        var clang = resolveClang();
        var pb = new ProcessBuilder(clang.toString(), "-O0", "-o", exeFile.toString(), cFile.toString());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var output = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) {
            throw new RuntimeException("clang failed compiling " + cFile + ":\n" + output);
        }
        return exeFile;
    }

    private static String run(Path exeFile) throws IOException, InterruptedException {
        var proc = new ProcessBuilder(exeFile.toAbsolutePath().toString()).redirectErrorStream(true).start();
        var output = new String(proc.getInputStream().readAllBytes());
        if (proc.waitFor() != 0) {
            throw new RuntimeException(exeFile + " failed:\n" + output);
        }
        return output;
    }

    private static Path resolveClang() {
        var envOverride = System.getenv("JR_CLANG");
        if (envOverride != null) {
            return Path.of(envOverride);
        }
        if (Files.isRegularFile(FALLBACK_CLANG)) {
            return FALLBACK_CLANG;
        }
        return Path.of("x86_64-w64-mingw32-clang");
    }
}
