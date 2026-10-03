package jarrunner.jr;

import static jarrunner.jr.N.*;

/** PRP-31: one Java home and jr's verdict on it. The version, the java.exe and the launch library always
 *  come from this one folder, so a launch can never check one Java and load another. */
public final class JavaHome {
    String home, source;
    int major;           // 0 = could not be read
    int machine;         // IMAGE_FILE_MACHINE_*, 0 = unknown
    String jli = "";     // bin\jli.dll for in-process mode, "" if it cannot be used in-process
    String reject;       // null = usable
    String releaseText;  // the release file, for the AOT cache identity

    private JavaHome() {}

    static JavaHome rejected(String home, String source, String why) {
        var h = new JavaHome();
        h.home = home;
        h.source = source;
        h.reject = why;
        return h;
    }

    static JavaHome inspect(String rawHome, String source, String exeName) {
        var home = normalize(rawHome);
        var h = rejected(home, source, null);
        if (!FileIo.exists(home)) {
            h.reject = "folder does not exist";
            return h;
        }
        if (!FileIo.exists(home + "\\bin\\" + exeName)) {
            h.reject = "no bin\\" + exeName;
            return h;
        }
        var jvmDll = firstExisting(home, "\\bin\\server\\jvm.dll", "\\jre\\bin\\server\\jvm.dll", "\\bin\\client\\jvm.dll",
                "\\jre\\bin\\client\\jvm.dll");
        h.releaseText = FileIo.readAll(home + "\\release");
        h.major = h.releaseText != null ? releaseMajor(h.releaseText) : 0;
        if (h.major == 0 && jvmDll != null) {
            h.major = JavaHomeProbe.dllMajor(jvmDll);
        }
        if (jvmDll == null || h.major == 0) {
            h.reject = jvmDll == null ? "no jvm.dll (incomplete or damaged install)" : "version cannot be read";
            return h;
        }
        h.machine = archFromRelease(h.releaseText);
        if (h.machine == 0 && jvmDll != null) {
            h.machine = JavaHomeProbe.peMachine(jvmDll);
        }
        if (!JavaHomeProbe.canRun(h.machine)) {
            h.reject = JavaHomeProbe.machineName(h.machine) + " Java cannot run on this " + JavaHomeProbe.machineName(JavaHomeProbe.nativeMachine()) + " computer";
            return h;
        }
        var jli = firstExisting(home, "\\bin\\jli.dll", "\\jre\\bin\\jli.dll");
        if (jli != null && jvmDll != null && (h.machine == 0 || h.machine == JavaHomeProbe.selfMachine())) {
            h.jli = jli;
        }
        return h;
    }

    boolean usable() {
        return reject == null;
    }

    String javaExe(String exeName) {
        return home + "\\bin\\" + exeName;
    }

    String describe() {
        return home + " (" + source + "): " + (reject != null ? (major > 0 ? "Java " + major + ", " : "") + reject
                : "Java " + major + (machine != 0 ? " " + JavaHomeProbe.machineName(machine) : "")
                + (jli.isEmpty() ? ", child process only" : ""));
    }

    /** A home path: clean(), with a trailing \bin (put in JAVA_HOME by mistake) dropped. */
    static String normalize(String path) {
        var p = clean(path);
        if (AsciiStr.endsWithIgnoreCase(p, "\\bin") && !FileIo.exists(p + "\\bin")) {
            p = p.substring(0, p.length() - 4);
        }
        return p;
    }

    /** A PATH entry or a file path: quotes and trailing separators stripped, / turned into \. */
    static String clean(String path) {
        var p = path.strip();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            p = p.substring(1, p.length() - 1).strip();
        }
        p = p.replace('/', '\\');
        while (p.length() > 3 && p.endsWith("\\")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String firstExisting(String home, String... rels) {
        for (var rel : rels) {
            if (FileIo.exists(home + rel)) {
                return home + rel;
            }
        }
        return null;
    }

    /** JAVA_VERSION="25.0.1" -> 25, "1.8.0_402" -> 8, a bare "25" -> 25. */
    static int releaseMajor(String text) {
        var v = releaseValue(text, "JAVA_VERSION");
        var major = Atoi.parse(v);
        if (major == 1 && v.length() > 2) {
            major = Atoi.parse(v.substring(2));
        }
        return Math.max(major, 0);
    }

    static String releaseValue(String text, String key) {
        if (text == null) {
            return "";
        }
        for (var line : Lines.split(text)) {
            if (line.startsWith(key + "=")) {
                var v = line.substring(key.length() + 1).strip();
                return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
            }
        }
        return "";
    }

    private static int archFromRelease(String text) {
        var a = AsciiStr.lower(releaseValue(text, "OS_ARCH"));
        return a.equals("amd64") || a.equals("x86_64") ? WinApi.IMAGE_FILE_MACHINE_AMD64
                : a.equals("aarch64") || a.equals("arm64") ? WinApi.IMAGE_FILE_MACHINE_ARM64
                : a.equals("x86") || a.equals("i386") || a.equals("i586") || a.equals("i686") ? WinApi.IMAGE_FILE_MACHINE_I386
                : 0;
    }
}
