package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

/** PRP-31: picks the Java home a launch uses, in the user's order: a local Java of exactly the preferred
 *  major; else download that major; else the nearest local one above it (up to max); else the nearest
 *  below it (down to min). Every candidate and its verdict is logged, and kept for the error message. */
public final class JavaChooser {
    final List<JavaHome> seen = new ArrayList<>();
    String rule = "";        // how the chosen home was picked, for the log and the doctor
    boolean hasConsole, guiMode, assumeYes, onlyCache;
    String packageType = "jre";

    JavaHome choose(JavaRange range, String exeName, boolean autoInstall) {
        var cacheRoot = JavaInstall.cacheRoot();
        JavaSources.quick(seen, exeName, cacheRoot, onlyCache);
        var exact = exact(range.preferred);
        if (exact == null && !onlyCache) {
            JavaSources.slow(seen, exeName);
            exact = exact(range.preferred);
        }
        for (var h : seen) {
            Log.info("Candidate " + h.describe());
        }
        if (exact != null) {
            rule = "exact match for preferred Java " + range.preferred;
            return exact;
        }
        if (autoInstall) {
            removeDamagedCache(cacheRoot, range.preferred);
            var home = JavaInstall.tryInstall(range.preferred, false, "Java " + range.preferred
                    + " was not found on this computer.", hasConsole, guiMode, assumeYes, cacheRoot, packageType);
            if (home != null) {
                var h = JavaHome.inspect(home, "downloaded", exeName);
                Log.info("Candidate " + h.describe());
                seen.add(h);
                if (h.usable() && range.accepts(h.major)) {
                    rule = "downloaded preferred Java " + range.preferred;
                    return h;
                }
            }
        }
        var best = nearest(range, true);
        if (best == null) {
            best = nearest(range, false);
        }
        if (best != null) {
            rule = "nearest installed Java to preferred " + range.preferred + " within " + range.describe();
        }
        return best;
    }

    private JavaHome exact(int major) {
        for (var h : seen) {
            if (h.usable() && h.major == major) {
                return h;
            }
        }
        return null;
    }

    /** above: the lowest major over preferred; otherwise the highest major under it. */
    private JavaHome nearest(JavaRange range, boolean above) {
        JavaHome best = null;
        for (var h : seen) {
            if (!h.usable() || !range.accepts(h.major) || (above ? h.major <= range.preferred : h.major >= range.preferred)) {
                continue;
            }
            if (best == null || (above ? h.major < best.major : h.major > best.major)) {
                best = h;
            }
        }
        return best;
    }

    /** A JDK jr (or jbang) downloaded that is now incomplete - an interrupted extract, a dll quarantined by
     *  antivirus - is deleted so it can be downloaded again. Only the slot about to be installed into. */
    private void removeDamagedCache(String cacheRoot, int major) {
        if (cacheRoot == null) {
            return;
        }
        for (var h : seen) {
            var name = Paths.fileNameOf(h.home);
            var slot = name.equals(String.valueOf(major)) || name.equals(major + "-jre");
            if (slot && !h.usable() && h.source.equals("jr cache") && !h.reject.contains("cannot run")) {
                Log.warn("Removing damaged Java in jr's cache (" + h.reject + "): " + h.home);
                Dirs.removeDirTree(h.home);
            }
        }
    }

    /** One line per candidate, for the "no suitable Java" message. */
    String report() {
        var sb = new StringBuilder();
        for (var h : seen) {
            sb.append("- ").append(h.describe()).append('\n');
        }
        return sb.length() == 0 ? "- no Java installation was found\n" : sb.toString();
    }
}
