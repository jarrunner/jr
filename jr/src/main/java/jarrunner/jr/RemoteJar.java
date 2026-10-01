package jarrunner.jr;

import static jarrunner.jr.N.*;

/**
 * PRP-24: a .jrc can name its jar remotely instead of by path - {@code run.url=<https url>} (a
 * GitHub release asset, or any direct link) or {@code run.maven=group:artifact:version[:classifier]}
 * (Maven Central) - pinned by a mandatory {@code run.sha256=<hex>}. Combined with an embedded .jrc
 * (Config.loadEmbedded) and the JRE auto-install, a single exe carries nothing but itself and
 * fetches the app and a Java on first run.
 *
 * The hash is required rather than fetched from beside the artifact: this downloads code and runs
 * it, and a hash served by the same host as the jar proves nothing if that host is compromised.
 * The .jrc author pins what they built.
 *
 * Where the jar lives: run.maven uses the standard ~/.m2/repository layout (PRP-26), run.url uses
 * jr's own %USERPROFILE%\.jr\cache\jars\&lt;sha256&gt;\ - see targetPath. Downloads go to a .part file
 * that a later run resumes (HTTP Range) if the connection drops, and the finished file is
 * hashed before it is moved into place.
 */
public final class RemoteJar {
    private RemoteJar() {}

    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /** The local path of the verified jar, or null after showing the user why not. */
    public static String resolve(Config config, boolean hasConsole, boolean guiMode) {
        var url = config.runUrl;
        String mavenRel = null;
        if (!config.runMaven.isEmpty()) {
            if (!url.isEmpty()) {
                return fail(hasConsole, "Set run.url or run.maven in the .jrc, not both.");
            }
            mavenRel = mavenPath(config.runMaven);
            if (mavenRel == null) {
                return fail(hasConsole, "run.maven must be group:artifact:version[:classifier], got:\n"
                        + config.runMaven);
            }
            url = MAVEN_CENTRAL + mavenRel;
        }
        if (!url.startsWith("https://")) {
            return fail(hasConsole, "run.url must be an https:// link, got:\n" + url);
        }
        var sha = AsciiStr.lower(config.runSha256);
        if (!isSha256(sha)) {
            return fail(hasConsole, "run.sha256 must be set to the jar's 64-character SHA-256 - it is how jr\n"
                    + "knows the download is the file you built. Got: '" + config.runSha256 + "'");
        }

        var target = targetPath(mavenRel, sha, url);
        if (target == null) {
            return fail(hasConsole, "USERPROFILE is not set, so there is nowhere to keep the jar.");
        }
        if (FileIo.exists(target)) {
            if (isVerified(target, sha)) {
                var changed = JarCheck.changedSinceVerified(config, target, sha);
                if (changed != null) {
                    return fail(hasConsole, changed);
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
            return fail(hasConsole, "A file already at this path does not match run.sha256, so it was not run:\n"
                    + target + "\nexpected " + sha + "\nactual   " + actual
                    + "\n\nEither run.sha256 is wrong, or the file is damaged - delete it and jr will download it again.");
        }

        Dirs.mkdirRecursive(target.substring(0, target.lastIndexOf('\\')));
        var part = target + ".part";
        Log.info("run target: downloading " + url);
        var progress = new Progress(guiMode, hasConsole, ExeInfo.baseNameNoExt() + " - Downloading",
                "Downloading " + fileNameOf(url) + "...");
        var ok = Http.downloadToFile(url, part, progress, true);
        progress.finish();
        if (!ok) {
            // The .part stays: the next run resumes it rather than starting over.
            return fail(hasConsole, "Could not download the application (the next run will resume):\n" + url);
        }
        var actual = Sha256.ofFile(part);
        if (actual == null || !AsciiStr.equalsIgnoreCase(actual, sha)) {
            WinApi.deleteFileA(cstr(part));
            return fail(hasConsole, "The downloaded application does not match run.sha256, so it was not run.\n\n"
                    + url + "\nexpected " + sha + "\nactual   " + actual);
        }
        if (WinApi.moveFileExA(cstr(part), cstr(target), 0) == 0 && !FileIo.exists(target)) {
            WinApi.deleteFileA(cstr(part));
            return fail(hasConsole, "Could not move the verified download into place:\n" + target);
        }
        markVerified(target, sha);
        Log.info("run target verified: " + target);
        return target;
    }

    /** run.maven: the standard local repository layout, so a jar Maven already has is reused and
     *  jr's downloads are visible to Maven (JR_M2_REPO overrides the root, for tests; a
     *  localRepository set in settings.xml is not read). run.url: jr's own content-addressed cache
     *  under %USERPROFILE%\.jr\cache\jars\&lt;sha256&gt;\ (JR_JAR_CACHE_DIR overrides). */
    private static String targetPath(String mavenRel, String sha, String url) {
        var profile = Cstr.readEnv("USERPROFILE");
        if (mavenRel != null) {
            var repo = Cstr.readEnv("JR_M2_REPO");
            if (repo == null || repo.isEmpty()) {
                if (profile == null || profile.isEmpty()) {
                    return null;
                }
                repo = profile + "\\.m2\\repository";
            }
            return repo + "\\" + mavenRel.replace('/', '\\');
        }
        var root = Cstr.readEnv("JR_JAR_CACHE_DIR");
        if (root == null || root.isEmpty()) {
            if (profile == null || profile.isEmpty()) {
                return null;
            }
            root = profile + "\\.jr\\cache\\jars";
        }
        return root + "\\" + sha + "\\" + fileNameOf(url);
    }

    /** A sidecar &lt;jar&gt;.jr-sha256 holding "&lt;sha256&gt; &lt;size&gt;", written after jr has hashed the jar
     *  itself, so a large shaded jar is not re-hashed on every launch. The size check catches a
     *  truncated or replaced file; same-size tampering is not caught, which is the same trust Maven
     *  itself places in its local repository. */
    private static boolean isVerified(String jar, String sha) {
        var text = FileIo.readAll(jar + ".jr-sha256");
        if (text == null) {
            return false;
        }
        var end = text.length();
        while (end > 0 && text.charAt(end - 1) <= ' ') {
            end--;
        }
        // "<sha256> <size>", plus " <crc32>" since PRP-30 (an older sidecar without it still counts)
        return (text.substring(0, end) + " ").startsWith(sha + " " + FileInfo.size(jar) + " ");
    }

    /** Written right after the SHA-256 matched; the CRC32 is what per-run verification compares
     *  against when the exe's config carries none of its own (an exe built before PRP-30). */
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

    /** group:artifact:version[:classifier] -> its repository-relative jar path (group/dirs/artifact/version/file.jar), or null if malformed. */
    static String mavenPath(String coords) {
        // Hand split, not String.split: that pulls java.util.regex into the TeaVM build (see
        // 11-prp.01.size-experiments.md).
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

    private static String fail(boolean hasConsole, String message) {
        Log.error(message);
        Ui.error(hasConsole, "Application Download", message);
        return null;
    }
}
