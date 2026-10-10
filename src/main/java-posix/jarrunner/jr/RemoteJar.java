package jarrunner.jr;

/**
 * The POSIX twin of the Windows RemoteJar (PRP-24, PRP-30; ported in PRP-36): a config can name its jar by
 * {@code run.url} / {@code run.maven}, or by jar.sources in a jrc-json, pinned by a mandatory SHA-256. Same
 * rules, same cache layout (~/.m2/repository for maven, ~/.jr/cache/jars/&lt;sha256&gt;/ for a url), same
 * .jr-sha256 sidecar, so a jar is hashed once and then checked cheaply each launch (JarCheck).
 *
 * The download runs curl (see Curl), which also shows its own progress
 * bar and resumes a .part file left by a dropped connection (-C -). The finished file is hashed before it is
 * moved into place.
 */
public final class RemoteJar {
    private RemoteJar() {}

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /** Why the last {@link #one} returned null. */
    private static String error;

    /** The local path of the verified jar, or null after showing why not. Sources are tried in order until one
     *  gives a verified jar; when all fail, the last one's reason is shown. */
    public static String resolve(Config config) {
        if (config.sources.isEmpty()) {
            if (!config.runUrl.isEmpty() && !config.runMaven.isEmpty()) {
                return fail("Set run.url or run.maven in the config, not both.");
            }
            var jar = one(config, config.runUrl, config.runMaven);
            return jar != null ? jar : fail(error);
        }
        // "u<url>\n" / "m<coords>\n" entries, from JrcJson
        var list = config.sources;
        for (var at = 0; at < list.length(); ) {
            var end = list.indexOf('\n', at);
            var s = list.substring(at + 1, end);
            var maven = list.charAt(at) == 'm';
            at = end + 1;
            var jar = one(config, maven ? "" : s, maven ? s : "");
            if (jar != null) return jar;
        }
        return fail(error);
    }

    private static String one(Config config, String url, String maven) {
        error = null;
        String mavenRel = null;
        if (!maven.isEmpty()) {
            mavenRel = mavenPath(maven);
            if (mavenRel == null) {
                return err("run.maven must be group:artifact:version[:classifier], got:\n" + maven);
            }
            url = MAVEN_CENTRAL + mavenRel;
        }
        if (!url.startsWith("https://")) {
            return err("run.url must be an https:// link, got:\n" + url);
        }
        var sha = AsciiStr.lower(config.runSha256);
        if (!isSha256(sha)) {
            return err("run.sha256 must be set to the jar's 64-character SHA-256 - it is how jr\n"
                    + "knows the download is the file you built. Got: '" + config.runSha256 + "'");
        }

        var target = targetPath(mavenRel, sha, url);
        if (target == null) {
            return err("HOME is not set, so there is nowhere to keep the jar.");
        }
        if (FileIo.exists(target)) {
            if (isVerified(target, sha)) {
                var changed = JarCheck.changedSinceVerified(config, target, sha);
                if (changed != null) {
                    return err(changed);
                }
                Log.info("run target present and verified: " + target);
                return target;
            }
            // Present but not verified by jr yet - e.g. Maven itself put it in ~/.m2. Hash it once.
            var actual = Sha256.ofFile(target);
            if (actual != null && AsciiStr.equalsIgnoreCase(actual, sha)) {
                markVerified(target, sha);
                Log.info("run target present, verified now: " + target);
                return target;
            }
            return err("A file already at this path does not match run.sha256, so it was not run:\n"
                    + target + "\nexpected " + sha + "\nactual   " + actual
                    + "\n\nEither run.sha256 is wrong, or the file is damaged - delete it and jr will download it again.");
        }

        mkdirs(target.substring(0, target.lastIndexOf('/')));
        var part = target + ".part";
        Log.info("run target: downloading " + url);
        Stderr.println("Downloading " + url);
        if (!Curl.download(url, part, true)) {
            // The .part stays: the next run resumes it rather than starting over.
            return err("Could not download the application (the next run will resume):\n" + url);
        }
        var actual = Sha256.ofFile(part);
        if (actual == null || !AsciiStr.equalsIgnoreCase(actual, sha)) {
            PosixApi.unlink(part);
            return err("The downloaded application does not match run.sha256, so it was not run.\n\n"
                    + url + "\nexpected " + sha + "\nactual   " + actual
                    + "\n\nThis app expects another jar: reinstall it.");
        }
        if (PosixApi.rename(part, target) != 0) {
            PosixApi.unlink(part);
            return err("Could not move the verified download into place:\n" + target);
        }
        markVerified(target, sha);
        Log.info("run target verified: " + target);
        return target;
    }

    /** run.maven: the standard local repository layout, so a jar Maven already has is reused (JR_M2_REPO
     *  overrides the root). run.url: jr's own content-addressed cache under ~/.jr/cache/jars/&lt;sha256&gt;/
     *  (JR_JAR_CACHE_DIR overrides). */
    private static String targetPath(String mavenRel, String sha, String url) {
        var home = Cstr.readEnv("HOME");
        if (mavenRel != null) {
            var repo = Cstr.readEnv("JR_M2_REPO");
            if (repo == null || repo.isEmpty()) {
                if (home == null || home.isEmpty()) {
                    return null;
                }
                repo = home + "/.m2/repository";
            }
            return repo + "/" + mavenRel;
        }
        var root = Cstr.readEnv("JR_JAR_CACHE_DIR");
        if (root == null || root.isEmpty()) {
            if (home == null || home.isEmpty()) {
                return null;
            }
            root = home + "/.jr/cache/jars";
        }
        return root + "/" + sha + "/" + fileNameOf(url);
    }

    private static void mkdirs(String dir) {
        for (var i = 1; i <= dir.length(); i++) {
            if (i == dir.length() || dir.charAt(i) == '/') {
                PosixApi.mkdir(dir.substring(0, i), (short) 0755); // mode_t: 16-bit on macOS, 32 on Linux
            }
        }
    }

    /** A sidecar &lt;jar&gt;.jr-sha256 holding "&lt;sha256&gt; &lt;size&gt; &lt;crc32&gt;", written after jr hashed
     *  the jar itself, as on Windows. */
    private static boolean isVerified(String jar, String sha) {
        var text = FileIo.readAll(jar + ".jr-sha256");
        if (text == null) {
            return false;
        }
        var end = text.length();
        while (end > 0 && text.charAt(end - 1) <= ' ') {
            end--;
        }
        return (text.substring(0, end) + " ").startsWith(sha + " " + FileInfo.size(jar) + " ");
    }

    private static void markVerified(String jar, String sha) {
        var crc = Crc32.ofFile(jar);
        FileIo.writeAll(jar + ".jr-sha256", sha + " " + FileInfo.size(jar) + (crc == null ? "" : " " + crc) + "\n");
    }

    /** The CRC32 recorded in the sidecar by markVerified, or null. */
    static String sidecarCrc(String jar) {
        var text = FileIo.readAll(jar + ".jr-sha256");
        if (text == null) return null;
        var line = text.strip();
        var first = line.indexOf(' ');
        var last = line.lastIndexOf(' ');
        return first >= 0 && last > first ? line.substring(last + 1) : null;
    }

    static void reverify(String jar, String sha) {
        markVerified(jar, sha);
    }

    /** group:artifact:version[:classifier] -> its repository-relative jar path, or null if malformed. */
    static String mavenPath(String coords) {
        // Hand split, not String.split: that pulls java.util.regex into the TeaVM build.
        var parts = new String[4];
        var count = 0;
        var start = 0;
        for (var i = 0; i <= coords.length(); i++) {
            if (i == coords.length() || coords.charAt(i) == ':') {
                if (i == start || count == 4) {
                    return null;
                }
                parts[count++] = coords.substring(start, i);
                start = i + 1;
            }
        }
        if (count < 3) {
            return null;
        }
        var group = parts[0].replace('.', '/');
        var file = parts[1] + "-" + parts[2] + (count == 4 ? "-" + parts[3] : "") + ".jar";
        return group + "/" + parts[1] + "/" + parts[2] + "/" + file;
    }

    private static String fileNameOf(String url) {
        var end = url.indexOf('?');
        var path = end < 0 ? url : url.substring(0, end);
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? "app.jar" : name;
    }

    private static boolean isSha256(String s) {
        if (s.length() != 64) {
            return false;
        }
        for (var i = 0; i < 64; i++) {
            var c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String err(String message) {
        Log.info("run target: " + message);
        error = message;
        return null;
    }

    private static String fail(String message) {
        Log.error(message);
        Ui.error(true, "Application Download", message);
        return null;
    }
}
