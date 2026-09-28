package memlab;

import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives TeaVM's C-backend directly via its tooling API (org.teavm.tooling.TeaVMTool),
 * bypassing the org.teavm:teavm-cli module - that module was not published to Maven
 * Central for 0.15.0 (only 0.14.1), and even at 0.14.1 its shaded -all.jar is broken
 * (relocates apache commons-cli but a leftover reference still points at the
 * unrelocated package, so `java -jar teavm-cli-*-all.jar` throws NoClassDefFoundError
 * on org.apache.commons.cli.ParseException before it even parses args).
 *
 * Usage: java -cp <teavm classpath>;target/classes pocapp.BuildDriver
 *            <classesDir> <targetDir> <mainClass> [extraClasspath;separated;by;semicolons]
 *
 * extraClasspath must include teavm-classlib, teavm-interop, teavm-platform and
 * teavm-core jars - TeaVMTool.setClassPath() defines the classpath the C backend
 * introspects to resolve BOTH your own classes AND java.lang.* /org.teavm.runtime.*
 * (its own runtime support classes); it does not fall back to the driver's own JVM
 * classpath for that.
 */
public class BuildDriver {
    public static void main(String[] args) throws Exception {
        File classesDir = new File(args[0]);
        File targetDir = new File(args[1]);
        String mainClass = args[2];
        String extraClasspath = args.length > 3 ? args[3] : "";

        List<File> classPath = new ArrayList<>();
        classPath.add(classesDir);
        for (String entry : extraClasspath.split(";")) {
            if (!entry.isBlank()) {
                classPath.add(new File(entry));
            }
        }

        TeaVMTool tool = new TeaVMTool();
        tool.setLog(new ConsoleTeaVMToolLog(true));
        tool.setTargetType(TeaVMTargetType.C);
        tool.setClassPath(classPath);
        tool.setMainClass(mainClass);
        tool.setTargetDirectory(targetDir);
        // These are raw BYTES, not megabytes despite teavm-cli's --min-heap/--max-heap
        // help text saying "in megabytes" (that CLI passes its parsed int straight
        // through with no *1024*1024). Passing small ints like 4/32 here reserves/
        // commits a too-small heap and segfaults on the first GC write, well past
        // this call (inside meth_otr_GC__clinit_), which makes it look unrelated.
        tool.setMinHeapSize(4 * 1024 * 1024);
        tool.setMaxHeapSize(32 * 1024 * 1024);

        // ADVANCED measured smaller than both SIMPLE (TeaVM's own default) and FULL (more
        // aggressive inlining actually grew the binary) - see 11-prp.01.size-experiments.md.
        // Still overridable via env for future experiments without recompiling this driver.
        String optLevel = System.getenv().getOrDefault("TEAVM_OPT_LEVEL", "ADVANCED");
        tool.setOptimizationLevel(TeaVMOptimizationLevel.valueOf(optLevel));
        tool.setAssertionsRemoved(Boolean.parseBoolean(System.getenv().getOrDefault("TEAVM_ASSERTIONS_REMOVED", "true")));
        System.out.println("[build] optLevel=" + optLevel
                + " assertionsRemoved=" + System.getenv().getOrDefault("TEAVM_ASSERTIONS_REMOVED", "true"));

        tool.generate();

        System.out.println("Generated files:");
        for (File f : tool.getGeneratedFiles()) {
            System.out.println("  " + f);
        }
        if (tool.getProblemProvider() != null && !tool.getProblemProvider().getProblems().isEmpty()) {
            System.out.println("Problems: " + tool.getProblemProvider().getProblems().size());
            tool.getProblemProvider().getProblems().forEach(p -> {
                System.out.println("  severity=" + p.getSeverity() + " text=" + p.getText());
                Object[] params = p.getParams();
                if (params != null) {
                    for (Object o : params) System.out.println("    param=" + o);
                }
                if (p.getLocation() != null) System.out.println("    location=" + p.getLocation());
            });
        }
    }
}
