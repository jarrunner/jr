import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Test app for PRP-05 (jvm.dll vs java.exe launch mode).
 *
 * Reports which OS process the JVM is actually running in, so the two launch
 * modes can be told apart: in java.exe mode the command is <jdk>\bin\java.exe,
 * in jvm.dll mode it is the launcher exe itself (jr.exe / myapp.exe).
 *
 * Args:
 *   --report=FILE  write the same report to FILE, plus whether the writes to
 *                  System.out actually reached anywhere (checkError). This is
 *                  what makes console output verifiable without reading the
 *                  console screen buffer.
 *   --stdin        echo one line read from stdin
 *   --exit=N       terminate with exit code N
 *   --sleep=N      stay alive N seconds, so it can be inspected in Task Manager
 */
public class JvmModeTest {
    public static void main(String[] args) throws Exception {
        ProcessHandle self = ProcessHandle.current();
        List<String> report = new ArrayList<>();

        report.add("pid          = " + self.pid());
        report.add("process      = " + self.info().command().orElse("<unknown>"));
        report.add("java.home    = " + System.getProperty("java.home"));
        report.add("java.version = " + System.getProperty("java.version"));
        report.add("sun.java.command = " + System.getProperty("sun.java.command"));
        report.add("launcher.start.micros = " + System.getProperty("jarrunner.start.micros"));
        report.add("console      = " + (System.console() != null));
        report.add("argc         = " + args.length);
        for (int i = 0; i < args.length; i++) {
            report.add("arg[" + i + "]       = [" + args[i] + "]");
        }

        String reportFile = null;
        int exitCode = 0;
        for (String a : args) {
            if (a.startsWith("--report=")) {
                reportFile = a.substring(9);
            } else if (a.startsWith("--exit=")) {
                exitCode = Integer.parseInt(a.substring(7));
            } else if (a.startsWith("--sleep=")) {
                Thread.sleep(Long.parseLong(a.substring(8)) * 1000L);
            }
        }

        for (String line : report) {
            System.out.println(line);
        }
        System.err.println("stderr works");

        for (String a : args) {
            if (a.equals("--stdin")) {
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                String line = in.readLine();
                report.add("stdin        = [" + line + "]");
                System.out.println("stdin        = [" + line + "]");
            }
        }

        System.out.println("done");

        // Whether the writes above actually landed somewhere. A broken stdout
        // handle makes PrintStream swallow the IOException and set this flag.
        System.out.flush();
        report.add("stdout.ok    = " + !System.out.checkError());
        System.err.flush();
        report.add("stderr.ok    = " + !System.err.checkError());

        if (reportFile != null) {
            try (PrintWriter w = new PrintWriter(reportFile, "UTF-8")) {
                for (String line : report) {
                    w.println(line);
                }
            }
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
