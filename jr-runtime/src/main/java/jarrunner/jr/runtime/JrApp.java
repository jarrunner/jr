package jarrunner.jr.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What jr told this app about itself. jr passes every leaf of the exe's embedded jrc-json as a
 *  system property {@code io.github.jarrunner.jr.<path>} (array elements as {@code .0}, {@code .1},
 *  ...), plus {@code exe}, {@code startMicros} and {@code beforeJvmMicros}. Nothing here is required
 *  reading: the properties are the API, and this class only gives them names and types.
 *  <p>
 *  Everything is empty when the app was not started by a jr exe (run with plain java, from an IDE,
 *  in tests), so an app can call it unconditionally. */
public final class JrApp {

    public static final String PREFIX = "io.github.jarrunner.jr.";

    private JrApp() {}

    /** True when a jr exe started this JVM. */
    public static boolean launchedByJr() {
        return property("exe").isPresent();
    }

    /** The launcher exe's full path, e.g. to tell the user what to run to update. */
    public static Optional<Path> exe() {
        return property("exe").map(Path::of);
    }

    /** The exe's file name without .exe, e.g. "demo", or empty. */
    public static Optional<String> exeName() {
        return exe().map(p -> p.getFileName().toString()).map(n -> n.toLowerCase().endsWith(".exe") ? n.substring(0, n.length() - 4) : n);
    }

    /** app.id from the jrc-json, by convention groupId:artifactId. */
    public static Optional<String> appId() {
        return config("app.id");
    }

    /** app.version from the jrc-json: the version this exe was built as. */
    public static Optional<String> appVersion() {
        return config("app.version");
    }

    public static Optional<String> updateUrl() {
        return config("update.url");
    }

    /** update.channel, "stable" when the jrc-json names none. */
    public static String updateChannel() {
        return config("update.channel").orElse("stable");
    }

    /** Any leaf of the jrc-json by its dotted path, e.g. "jvm.mode" or "jar.sources.0.url". */
    public static Optional<String> config(String path) {
        return property(path);
    }

    /** A list from the jrc-json by its path, e.g. "app.args": path.0, path.1, ... until one is missing. */
    public static List<String> configList(String path) {
        var out = new ArrayList<String>();
        for (var i = 0; ; i++) {
            var v = property(path + "." + i);
            if (v.isEmpty()) return out;
            out.add(v.get());
        }
    }

    private static Optional<String> property(String key) {
        var v = System.getProperty(PREFIX + key);
        return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v);
    }
}
