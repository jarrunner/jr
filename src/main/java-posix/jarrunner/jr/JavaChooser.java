package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

/** PRP-31, POSIX twin of the Windows chooser: a candidate is a Java HOME with bin/java and a readable release
 *  file, never a bare java binary; a version that cannot be read rejects the candidate. Sources: PATH (resolved
 *  through symlinks, so /usr/bin/java -> /etc/alternatives -> the real home), JAVA_HOME, then the usual install
 *  roots on macOS and Linux. Order: exact preferred, else nearest above (up to max), else nearest below (down to
 *  min). No download on this build. Nothing is ever executed: macOS's /usr/bin/java stub is only resolved, and
 *  without a JDK behind it it is simply rejected. */
public final class JavaChooser {
    final List<String> homes = new ArrayList<>();
    final List<Integer> majors = new ArrayList<>();
    final StringBuilder report = new StringBuilder();
    String rule = "";

    String choose(JavaRange range) {
        var path = JavaFinder.findInPath("java");
        if (path != null) {
            add(JavaFinder.homeOf(path), "PATH");
        }
        var javaHome = Cstr.readEnv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            add(javaHome, "JAVA_HOME");
        }
        var user = Cstr.readEnv("HOME");
        scan("/Library/Java/JavaVirtualMachines", "/Contents/Home");
        if (user != null) {
            scan(user + "/Library/Java/JavaVirtualMachines", "/Contents/Home");
            scan(user + "/.sdkman/candidates/java", "");
            scan(user + "/.jdks", "");
            scan(user + "/.jbang/cache/jdks", "");
            scan(user + "/.local/share/mise/installs/java", "");
            scan(user + "/.asdf/installs/java", "");
        }
        for (var brew : new String[] {"/opt/homebrew/opt", "/usr/local/opt"}) {
            for (var name : Dirs.listDirNames(brew)) {
                if (name.startsWith("openjdk")) add(brew + "/" + name + "/libexec/openjdk.jdk/Contents/Home", "Homebrew");
            }
        }
        scan("/usr/lib/jvm", "");
        scan("/opt/java", "");
        String best = null;
        var bestMajor = 0;
        for (var i = 0; i < homes.size(); i++) {
            var m = majors.get(i);
            if (!range.accepts(m)) continue;
            if (m == range.preferred) {
                rule = "exact match for preferred Java " + m;
                return homes.get(i);
            }
            var better = best == null || (m > range.preferred
                    ? bestMajor < range.preferred || m < bestMajor
                    : bestMajor < range.preferred && m > bestMajor);
            if (better) {
                best = homes.get(i);
                bestMajor = m;
            }
        }
        rule = best == null ? "" : "nearest installed Java to preferred " + range.preferred + " within " + range.describe();
        return best;
    }

    private void scan(String root, String suffix) {
        for (var name : Dirs.listDirNames(root)) {
            add(root + "/" + name + suffix, root);
        }
    }

    private void add(String home, String source) {
        if (home == null || home.isEmpty() || homes.contains(home)) return;
        var major = FileIo.exists(home + "/bin/java") ? JavaFinder.releaseMajor(home) : -1;
        var verdict = major < 0 ? "no bin/java" : major == 0 ? "version cannot be read" : "Java " + major;
        Log.info("Candidate " + home + " (" + source + "): " + verdict);
        report.append("- ").append(home).append(" (").append(source).append("): ").append(verdict).append('\n');
        if (major > 0) {
            homes.add(home);
            majors.add(major);
        }
    }
}
