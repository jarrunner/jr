package jarrunner.jr;

import java.util.ArrayList;

/** PRP-42: the POSIX twin of the Windows JavaInstall. Downloads Eclipse Temurin of the preferred major version for
 *  this OS and architecture from the Foojay Disco API (the one jbang uses) into jbang's cache layout
 *  (~/.jbang/cache/jdks/&lt;N&gt; for a JDK, &lt;N&gt;-jre for a JRE), so the two tools share downloads and JavaChooser
 *  finds it on later runs. Asks first (Ui.confirm) unless -Xjr:yes; checks the archive's SHA-256 before unpacking it
 *  with the system tar. On macOS the archive holds a .jdk bundle: its Contents/Home becomes the cache slot, so the
 *  slot has bin/java at its top like every other entry there. */
public final class JavaInstall {
    private JavaInstall() {}

    /** JR_JDK_CACHE_DIR (tests), else jbang's cache; null without HOME. */
    static String cacheRoot() {
        var o = Cstr.readEnv("JR_JDK_CACHE_DIR");
        if (o != null && !o.isEmpty()) return o;
        var home = Cstr.readEnv("HOME");
        return home == null || home.isEmpty() ? null : home + "/.jbang/cache/jdks";
    }

    /** The installed Java home, or null (declined, or failed: the reason is logged and, when asked, shown). */
    static String tryInstall(int version, String packageType, boolean assumeYes) {
        var root = cacheRoot();
        if (root == null) return null;
        var jdk = packageType.equals("jdk");
        var label = jdk ? "JDK" : "JRE";
        var target = root + "/" + version + (jdk ? "" : "-jre");
        if (FileIo.exists(target + "/bin/java")) return target;
        var machine = NativeArch.machine();
        var arch = machine.equals("arm64") || machine.equals("aarch64") ? "aarch64" : "x64";
        if (!assumeYes && !Ui.confirm("Java Not Found", "This app needs Java " + version + ", which is not on this computer.\n\n"
                + "Download Eclipse Temurin " + label + " " + version + " now? It is about 50 MB, and goes to\n" + target, "Download")) {
            return null;
        }
        var tmp = root + "/.jr-install";
        run("/bin/rm", "-rf", tmp);
        mkdirs(tmp + "/x");

        String id = null;
        var types = jdk ? new String[] {"jdk"} : new String[] {"jre", "jdk"}; // a JDK serves where no JRE is published
        for (var i = 0; i < types.length && id == null; i++) {
            var q = Curl.text("https://api.foojay.io/disco/v3.0/packages?distro=temurin&javafx_bundled=false"
                    + "&directly_downloadable=true&archive_type=tar.gz&operating_system=" + (Os.LINUX ? "linux&libc_type=glibc" : "macos")
                    + "&package_type=" + types[i] + "&release_status=ga&architecture=" + arch + "&latest=available&version=" + version,
                    tmp + "/packages.json", 1 << 20);
            id = q == null ? null : str(JsonReader.parse(q).path("result").at(0), "id");
        }
        if (id == null) return failed(tmp, "Found no Eclipse Temurin " + version + " for " + arch + " (api.foojay.io).");
        var ids = Curl.text("https://api.foojay.io/disco/v3.0/ids/" + id, tmp + "/ids.json", 1 << 20);
        var pkg = ids == null ? null : JsonReader.parse(ids).path("result").at(0);
        var url = str(pkg, "direct_download_uri");
        var sha = str(pkg, "checksum");
        if (url == null || sha == null || !url.startsWith("https://")) return failed(tmp, "api.foojay.io gave no download for package " + id + ".");

        Ui.notify("Downloading Java " + version, "Eclipse Temurin " + label + " " + version + ", about 50 MB. The app opens when it is done.");
        var archive = tmp + "/java.tar.gz";
        if (!Curl.download(url, archive, true)) return failed(tmp, "The download failed:\n" + url);
        var got = Sha256.ofFile(archive);
        if (got == null || !AsciiStr.equalsIgnoreCase(got, sha)) return failed(tmp, "The download's SHA-256 does not match (expected " + sha + ", got " + got + ").");
        if (run("/usr/bin/tar", "-xzf", archive, "-C", tmp + "/x") != 0) return failed(tmp, "Could not unpack " + archive);
        var top = Dirs.listDirNames(tmp + "/x");
        if (top.size() != 1) return failed(tmp, "The archive does not hold one folder.");
        var home = tmp + "/x/" + top.get(0);
        if (FileIo.exists(home + "/Contents/Home/bin/java")) home = home + "/Contents/Home";
        if (!FileIo.exists(home + "/bin/java")) return failed(tmp, "The archive has no bin/java.");
        run("/bin/rm", "-rf", target); // a damaged earlier attempt
        if (PosixApi.rename(home, target) != 0) return failed(tmp, "Could not move the new Java to " + target);
        run("/bin/rm", "-rf", tmp);
        Log.info("Installed Java " + version + " (" + label + ", " + arch + ") in " + target);
        return target;
    }

    private static String str(JsonValue o, String key) {
        var v = o == null ? null : o.get(key);
        return v == null ? null : v.str();
    }

    private static String failed(String tmp, String why) {
        run("/bin/rm", "-rf", tmp);
        Log.warn("Java download: " + why);
        Ui.error(true, "Java Download Failed", why);
        return null;
    }

    private static int run(String exe, String... args) {
        var list = new ArrayList<String>();
        for (var a : args) list.add(a);
        var r = ProcessLauncher.launch(exe, list);
        return r.started ? r.exitCode : -1;
    }

    private static void mkdirs(String dir) {
        for (var i = 1; i <= dir.length(); i++) {
            if (i == dir.length() || dir.charAt(i) == '/') PosixApi.mkdir(dir.substring(0, i), (short) 0755);
        }
    }
}
