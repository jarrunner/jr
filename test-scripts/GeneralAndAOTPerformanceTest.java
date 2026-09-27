///usr/bin/env jbang "$0" "$@" ; exit $?
// Standalone Java code (not part of main project) - replaces bash/python/batch scripts with IDE-friendly, maintainable code using JDK 11/21/25 enhancements. To know why, refer to Cay Horstmann's JavaOne 2025 talk "Java for Small Coding Tasks" (https://youtu.be/04wFgshWMdA)

//DEPS org.apache.commons:commons-lang3:3.14.0
//DEPS com.google.guava:guava:33.0.0-jre
//DEPS com.fasterxml.jackson.core:jackson-databind:2.16.1

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.util.*;

/**
 * General and AOT Performance Test
 * Comprehensive timing test with proper millisecond-based measurements
 * Tracks timing from jarrunner.exe start through to program exit
 * Tests both regular execution and AOT cache performance
 */
public class GeneralAndAOTPerformanceTest {

    // Static initializer runs BEFORE main()
    private static final long CLASS_INIT_TIME_MS = System.currentTimeMillis();

    public static void main(String[] args) {
        // Record when main() actually starts
        long mainStartTimeMs = System.currentTimeMillis();

        System.out.println("================================================================================");
        System.out.println("             COMPREHENSIVE TIMING ANALYSIS");
        System.out.println("================================================================================");
        System.out.println();

        // Get JVM runtime information
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        long jvmStartTimeMs = runtimeMXBean.getStartTime();
        long jvmUptimeMs = runtimeMXBean.getUptime();

        // Parse jarrunner timing from system properties
        String jarrunnerStartMicrosStr = System.getProperty("jarrunner.start.micros");
        String jarrunnerBeforeJVMMicrosStr = System.getProperty("jarrunner.beforejvm.micros");

        Long jarrunnerStartMs = null;
        Long jarrunnerBeforeJVMMs = null;
        Long launcherOverheadMs = null;

        if (jarrunnerStartMicrosStr != null && jarrunnerBeforeJVMMicrosStr != null) {
            try {
                long startMicros = Long.parseLong(jarrunnerStartMicrosStr);
                long beforeJVMMicros = Long.parseLong(jarrunnerBeforeJVMMicrosStr);

                // Convert microseconds to milliseconds (with precision loss, but matches our Java timing)
                jarrunnerStartMs = startMicros / 1000;
                jarrunnerBeforeJVMMs = beforeJVMMicros / 1000;
                launcherOverheadMs = jarrunnerBeforeJVMMs - jarrunnerStartMs;
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }

        // Check for AOT by examining JVM arguments
        List<String> jvmArgs = runtimeMXBean.getInputArguments();
        boolean aotEnabled = false;
        String aotMode = "NONE";
        String aotCachePath = null;

        for (String arg : jvmArgs) {
            if (arg.startsWith("-XX:AOTCache=")) {
                aotEnabled = true;
                aotMode = "USE (reusing cache)";
                aotCachePath = arg.substring("-XX:AOTCache=".length());
                break;
            } else if (arg.startsWith("-XX:AOTCacheOutput=")) {
                aotEnabled = true;
                aotMode = "CREATE (first run)";
                aotCachePath = arg.substring("-XX:AOTCacheOutput=".length());
                break;
            }
        }

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("PHASE 1: LAUNCHER & JVM INITIALIZATION");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        if (jarrunnerStartMs != null) {
            System.out.println("[T+0ms]      jarrunner.exe started");
            System.out.printf ("[T+%dms]    JVM invoked (launcher overhead: %d ms)%n",
                launcherOverheadMs, launcherOverheadMs);
        } else {
            System.out.println("[T+?ms]      jarrunner.exe started (timing not available)");
        }

        long timeFromJVMToClassInit = CLASS_INIT_TIME_MS - jvmStartTimeMs;
        long timeFromJVMToMain = mainStartTimeMs - jvmStartTimeMs;

        System.out.printf ("[T+%dms]    Static initializer executed (JVM->ClassInit: %d ms)%n",
            timeFromJVMToClassInit, timeFromJVMToClassInit);
        System.out.printf ("[T+%dms]    main() started (JVM->main: %d ms)%n",
            timeFromJVMToMain, timeFromJVMToMain);
        System.out.println();

        System.out.println("AOT Status:");
        System.out.println("  Enabled:  " + aotEnabled);
        System.out.println("  Mode:     " + aotMode);
        if (aotCachePath != null) {
            System.out.println("  Path:     " + aotCachePath);
        }
        System.out.println();

        // PHASE 2: Library Loading
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("PHASE 2: LIBRARY INITIALIZATION");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        long libraryLoadStartMs = System.currentTimeMillis();

        // 1. Guava
        long guavaStartMs = System.currentTimeMillis();
        ImmutableList<String> list = ImmutableList.of("one", "two", "three", "four", "five");
        ImmutableMap<String, Integer> map = ImmutableMap.of("a", 1, "b", 2, "c", 3);
        String joined = String.join(", ", list);
        long guavaEndMs = System.currentTimeMillis();
        long guavaTimeMs = guavaEndMs - guavaStartMs;

        System.out.printf("Guava (com.google.common):       %4d ms%n", guavaTimeMs);

        // 2. Commons Lang
        long commonsStartMs = System.currentTimeMillis();
        String padded = StringUtils.rightPad("test", 20, '*');
        String reversed = StringUtils.reverse("Hello World");
        boolean isNumeric = StringUtils.isNumeric("12345");
        long commonsEndMs = System.currentTimeMillis();
        long commonsTimeMs = commonsEndMs - commonsStartMs;

        System.out.printf("Commons Lang (org.apache):       %4d ms%n", commonsTimeMs);

        // 3. Jackson
        long jacksonStartMs = System.currentTimeMillis();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("name", "test");
        node.put("value", 123);
        node.put("timestamp", Instant.now().toString());
        String json = node.toString();
        long jacksonEndMs = System.currentTimeMillis();
        long jacksonTimeMs = jacksonEndMs - jacksonStartMs;

        System.out.printf("Jackson (com.fasterxml):         %4d ms%n", jacksonTimeMs);

        long libraryLoadEndMs = System.currentTimeMillis();
        long totalLibraryTimeMs = libraryLoadEndMs - libraryLoadStartMs;

        System.out.println("                                 ───────");
        System.out.printf("Total library loading:           %4d ms%n", totalLibraryTimeMs);
        System.out.println();

        // PHASE 3: Application Work
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("PHASE 3: APPLICATION LOGIC");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        long workStartMs = System.currentTimeMillis();

        // Simulate some application work
        System.out.println("Processing command line arguments:");
        if (args.length == 0) {
            System.out.println("  (no arguments)");
        } else {
            for (int i = 0; i < args.length; i++) {
                System.out.printf("  args[%d]: %s%n", i, args[i]);
            }
        }

        long workEndMs = System.currentTimeMillis();
        long workTimeMs = workEndMs - workStartMs;

        System.out.printf("%nApplication work completed in: %d ms%n", workTimeMs);
        System.out.println();

        // PHASE 4: Exit preparation
        long exitPrepStartMs = System.currentTimeMillis();

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println("COMPLETE TIMELINE SUMMARY");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();

        long totalElapsedMs = exitPrepStartMs - jvmStartTimeMs;

        System.out.println("┌─ Timeline (all times in milliseconds) ─────────────────────────────────────┐");
        System.out.println("│                                                                             │");

        if (jarrunnerStartMs != null && launcherOverheadMs != null) {
            System.out.printf("│  [T+0]       jarrunner.exe starts                                          │%n");
            System.out.printf("│  [T+%-4d]    JVM invoked (C code overhead: %-4d ms)                        │%n",
                launcherOverheadMs, launcherOverheadMs);
        }

        System.out.printf("│  [T+0]       JVM process starts (reference point)                          │%n");
        System.out.printf("│  [T+%-4d]    Static initializers run (JVM init: %-4d ms)                   │%n",
            timeFromJVMToClassInit, timeFromJVMToClassInit);
        System.out.printf("│  [T+%-4d]    main() begins (ClassInit->main: %-4d ms)                       │%n",
            timeFromJVMToMain, timeFromJVMToMain - timeFromJVMToClassInit);

        long libraryStartFromJVM = libraryLoadStartMs - jvmStartTimeMs;
        long libraryEndFromJVM = libraryLoadEndMs - jvmStartTimeMs;

        System.out.printf("│  [T+%-4d]    Library loading starts                                        │%n",
            libraryStartFromJVM);
        System.out.printf("│  [T+%-4d]    Library loading complete (libraries: %-4d ms)                 │%n",
            libraryEndFromJVM, totalLibraryTimeMs);

        long workStartFromJVM = workStartMs - jvmStartTimeMs;
        long workEndFromJVM = workEndMs - jvmStartTimeMs;

        System.out.printf("│  [T+%-4d]    Application work starts                                       │%n",
            workStartFromJVM);
        System.out.printf("│  [T+%-4d]    Application work complete (work: %-4d ms)                     │%n",
            workEndFromJVM, workTimeMs);
        System.out.printf("│  [T+%-4d]    Preparing to exit                                             │%n",
            totalElapsedMs);
        System.out.println("│                                                                             │");
        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
        System.out.println();

        System.out.println("╔═════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         PERFORMANCE BREAKDOWN                               ║");
        System.out.println("╠═════════════════════════════════════════════════════════════════════════════╣");

        if (launcherOverheadMs != null) {
            System.out.printf("║  Launcher (C code):              %6d ms                                 ║%n",
                launcherOverheadMs);
        }

        System.out.printf("║  JVM Initialization:             %6d ms                                 ║%n",
            timeFromJVMToClassInit);
        System.out.printf("║  Class loading to main():        %6d ms                                 ║%n",
            timeFromJVMToMain - timeFromJVMToClassInit);
        System.out.printf("║  Library loading:                %6d ms  (Guava: %d, Commons: %d, Jackson: %d) ║%n",
            totalLibraryTimeMs, guavaTimeMs, commonsTimeMs, jacksonTimeMs);
        System.out.printf("║  Application work:               %6d ms                                 ║%n",
            workTimeMs);
        System.out.println("║                                  ────────                                   ║");
        System.out.printf("║  TOTAL TIME (JVM start->exit):   %6d ms                                 ║%n",
            totalElapsedMs);

        if (jarrunnerStartMs != null && launcherOverheadMs != null) {
            long grandTotal = launcherOverheadMs + totalElapsedMs;
            System.out.printf("║  GRAND TOTAL (jarrunner->exit):  %6d ms                                 ║%n",
                grandTotal);
        }

        System.out.println("╚═════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Show AOT impact analysis
        if (aotEnabled) {
            System.out.println("╔═════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║                           AOT ANALYSIS                                      ║");
            System.out.println("╠═════════════════════════════════════════════════════════════════════════════╣");
            System.out.println("║  AOT Mode: " + String.format("%-62s", aotMode) + "║");

            if (aotMode.contains("CREATE")) {
                System.out.println("║                                                                             ║");
                System.out.println("║  This is the FIRST RUN - creating AOT cache.                               ║");
                System.out.println("║  Run again with -Xjr:aot=true to see performance improvement!               ║");
            } else if (aotMode.contains("USE")) {
                System.out.println("║                                                                             ║");
                System.out.println("║  Using AOT cache - startup should be SIGNIFICANTLY faster!                 ║");
                System.out.println("║  Compare this timing with a run WITHOUT -Xjr:aot=true flag.                 ║");
            }

            System.out.println("╚═════════════════════════════════════════════════════════════════════════════╝");
            System.out.println();
        }

        long exitTimeMs = System.currentTimeMillis();
        long timeInExitPrep = exitTimeMs - exitPrepStartMs;

        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("Test completed. Time spent generating this report: %d ms%n", timeInExitPrep);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════");
        System.out.println();
    }
}
