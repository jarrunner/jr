package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

import static jarrunner.jr.N.*;

/** -Xjr:update on macOS and Linux (PRP-42): the POSIX twin of the Windows SelfUpdate (PRP-30). It replaces this binary
 *  with the newest release on its channel, the yt-dlp way, and never touches anything before the download's sha256
 *  has been checked.
 *  <ul>
 *  <li>A bare binary: the update is downloaded beside it as &lt;binary&gt;.jr-download, given the binary's own mode,
 *      and renamed over it. rename(2) swaps the name atomically, so there is never a moment without a binary, and the
 *      running process keeps its old file. The new file is a new inode, which macOS requires: it caches a binary's
 *      code signature per inode, and a signed binary overwritten in place is killed on its next launch.</li>
 *  <li>Inside an .app (this binary in X.app/Contents/MacOS/): an .app this binary writes itself (AppBundle: unsealed, and the binary carries its bundle files) gets the new binary as above and is rewritten from it on the next launch. Any other .app: the whole bundle is replaced, never just the binary,
 *      which would break the bundle's seal. The release's app zip is downloaded beside the bundle, unpacked with
 *      ditto (which keeps permissions and adds no quarantine mark), and swapped in with two renames; the old bundle is
 *      put back if the second one fails.</li>
 *  </ul>
 *  The update file's entries: "exe" for bare binaries, "app" for .app zips, keyed by platform (Os.updateKeys). */
public final class SelfUpdate {
    /** What the last run did, for -Xjr:batch (UpdateResult): its status, the version it updated to or found, and what it said. */
    public static String status, latest, said;

    private SelfUpdate() {}

    public static int run(Config c) {
        var check = UpdateCheck.run(c);
        if (check.status != UpdateCheck.NEWER) {
            var code = report(check.status == UpdateCheck.ERROR, check.message);
            if (check.status == UpdateCheck.CURRENT) status = UpdateResult.CURRENT;
            return code;
        }
        latest = check.release.get("version").str();
        var exe = ExeInfo.fullPath();
        if (exe.isEmpty()) return report(true, "Could not find this binary's own path, so it cannot be replaced.");
        var at = exe.lastIndexOf(".app/Contents/MacOS/");
        var bundle = at < 0 ? null : exe.substring(0, at + 4);
        if (bundle != null && !FileIo.exists(bundle + "/Contents/_CodeSignature") && AppBundle.embedded() != null
                && entryFor(check.release, "exe") != null) {
            bundle = null; // an .app this binary writes itself: replace the binary; the next launch rewrites the bundle (AppBundle)
        }
        var target = bundle != null ? bundle : exe;
        var section = bundle != null ? "app" : "exe";

        var dir = Paths.dirOf(target);
        if (exe.contains("/AppTranslocation/")) { // macOS runs a quarantined app from a read-only copy until it is moved
            return report(true, "macOS is running this app from a temporary read-only copy, so it cannot be updated.\n"
                    + "Move " + Paths.fileNameOf(target) + " to Applications, open it from there, and update again.");
        }
        if (PosixApi.access(dir, PosixApi.W_OK) != 0) {
            return report(true, "jr cannot write to " + dir + ", so it cannot replace " + Paths.fileNameOf(target)
                    + ".\nNothing was changed. Reinstall it somewhere you can write to, or install the new version by hand.");
        }

        var entry = entryFor(check.release, section);
        if (entry == null) {
            return report(true, "The newest release has no " + (bundle != null ? "app" : "binary") + " for this computer ("
                    + section + ": " + keyList() + ").");
        }
        var sha = entry.get("sha256") == null ? null : entry.get("sha256").str();
        var urls = entry.get("urls");
        if (sha == null || sha.length() != 64 || urls == null || urls.size() == 0) {
            return report(true, "The update file's " + section + " entry for this computer needs a sha256 and at least one url.");
        }

        var download = target + (bundle != null ? ".jr-download.zip" : ".jr-download");
        var got = false;
        for (var i = 0; i < urls.size() && !got; i++) {
            var url = urls.at(i).str();
            if (url == null || !url.startsWith("https://")) continue;
            Stderr.println("Downloading " + url);
            var ok = Curl.download(url, download, false);
            var actual = ok ? Sha256.ofFile(download) : null;
            got = actual != null && AsciiStr.equalsIgnoreCase(actual, sha);
            if (!got) {
                Log.warn("update: " + url + (ok ? " did not match its sha256" : " could not be downloaded"));
                PosixApi.unlink(download);
            }
        }
        if (!got) return report(true, "Could not download a verified update. Nothing was changed.");

        var problem = bundle != null ? replaceBundle(bundle, ExeInfo.baseNameNoExt(), download) : replaceBinary(exe, download);
        if (problem != null) return report(true, problem);
        return report(false, "Updated: " + c.appVersion + " -> " + check.release.get("version").str()
                + ". The new version runs from the next launch.");
    }

    /** The release's entry in section for the first of this computer's platform keys that it has, or null. */
    private static JsonValue entryFor(JsonValue release, String section) {
        for (var k : Os.updateKeys()) {
            var e = release.path(section, k);
            if (e != null) return e;
        }
        return null;
    }

    /** Gives the verified download the binary's mode (curl writes it 0644) and renames it over the binary. */
    private static String replaceBinary(String exe, String download) {
        var mode = mode(exe);
        if (mode < 0 || PosixApi.chmod(download, (short) mode) != 0) {
            PosixApi.unlink(download);
            return "Could not make the update executable. Nothing was changed.";
        }
        if (PosixApi.rename(download, exe) != 0) {
            PosixApi.unlink(download);
            return "Could not move the update into place. Nothing was changed.";
        }
        return null;
    }

    /** Unpacks the verified app zip beside the bundle and swaps the new bundle in. */
    private static String replaceBundle(String bundle, String exeName, String zip) {
        var unpacked = bundle + ".jr-new";
        var old = bundle + ".jr-replaced";
        removeTree(unpacked);
        removeTree(old);
        var ditto = ProcessLauncher.launch("/usr/bin/ditto", args("-x", "-k", zip, unpacked));
        PosixApi.unlink(zip);
        if (!ditto.started || ditto.exitCode != 0) {
            removeTree(unpacked);
            return "Could not unpack the update (ditto). Nothing was changed.";
        }
        var app = singleApp(unpacked);
        if (app == null || !FileIo.exists(app + "/Contents/MacOS/" + exeName)) {
            removeTree(unpacked);
            return "The update is not an app with Contents/MacOS/" + exeName + ". Nothing was changed.";
        }
        if (PosixApi.rename(bundle, old) != 0) {
            removeTree(unpacked);
            return "macOS did not let jr move " + Paths.fileNameOf(bundle) + " aside, so nothing was changed.\n"
                    + "If System Settings, Privacy & Security, App Management lists this app, allow it there and try again,"
                    + " or install the new version by hand.";
        }
        if (PosixApi.rename(app, bundle) != 0) {
            PosixApi.rename(old, bundle);
            removeTree(unpacked);
            return "Could not move the new app into place. The old app was restored.";
        }
        removeTree(old); // this process keeps running from its open file; the new bundle is in place
        removeTree(unpacked);
        return null;
    }

    /** The one X.app directly inside dir, or null. */
    private static String singleApp(String dir) {
        String found = null;
        for (var name : Dirs.listDirNames(dir)) {
            if (!name.endsWith(".app")) continue;
            if (found != null) return null;
            found = dir + "/" + name;
        }
        return found;
    }

    /** rm -rf, for jr's own scratch folders beside the bundle (each name ends in .jr-new or .jr-replaced). */
    private static void removeTree(String path) {
        if (FileIo.exists(path)) ProcessLauncher.launch("/bin/rm", args("-rf", path));
    }

    /** A plain ArrayList: List.of and String.join pull more of TeaVM's class library into the binary. */
    private static List<String> args(String... a) {
        var out = new ArrayList<String>();
        for (var s : a) out.add(s);
        return out;
    }

    private static String keyList() {
        var b = new StringBuilder();
        for (var k : Os.updateKeys()) b.append(b.length() == 0 ? "" : " or ").append(k);
        return b.toString();
    }

    /** The permission bits of path, or -1. */
    private static int mode(String path) {
        var buf = alloc(PosixOffsets.stat_t.SIZE);
        return PosixApi.stat(path, buf) != 0 ? -1 : PosixOffsets.stat_t.st_mode(buf) & 07777;
    }

    private static int report(boolean error, String message) {
        status = error ? UpdateResult.ERROR : UpdateResult.UPDATED;
        said = message;
        if (error) {
            Ui.error(true, "Update", message);
            return 1;
        }
        Ui.info(true, "Update", message);
        return 0;
    }
}
