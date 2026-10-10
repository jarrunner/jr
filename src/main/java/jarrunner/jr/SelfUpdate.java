package jarrunner.jr;

import static jarrunner.jr.N.*;

/** -Xjr:update: replaces this exe with the newest release on its channel, the yt-dlp way (PRP-30).
 *  Downloads beside the exe, checks the sha256 before touching anything, renames the running exe
 *  to &lt;exe&gt;.jr-replaced (Windows allows renaming a running exe, not overwriting it), moves the
 *  new one into place, and puts the old one back if that fails. A later launch deletes the
 *  .jr-replaced file (Jr.main). Works even when the app's own jar is broken. */
public final class SelfUpdate {
    private static final int MOVEFILE_REPLACE_EXISTING = 1; // winbase.h
    public static final String REPLACED = ".jr-replaced";

    /** What the last run did, for -Xjr:batch (UpdateResult): its status, the version it updated to or found, and what it said. */
    public static String status, latest, said;

    private SelfUpdate() {}

    public static int run(Config c, boolean hasConsole, boolean guiMode) {
        var check = UpdateCheck.run(c);
        if (check.status != UpdateCheck.NEWER) {
            var code = report(hasConsole, check.status == UpdateCheck.ERROR, check.message);
            if (check.status == UpdateCheck.CURRENT) status = UpdateResult.CURRENT;
            return code;
        }
        latest = check.release.get("version").str();
        var key = "aarch64".equals(NativeArch.foojayName()) ? "windows-aarch64" : "windows-x86_64";
        var entry = check.release.path("exe", key);
        if (entry == null && key.equals("windows-aarch64")) entry = check.release.path("exe", "windows-x86_64");
        if (entry == null) return report(hasConsole, true, "The newest release has no exe for " + key + ".");
        var sha = entry.get("sha256") == null ? null : entry.get("sha256").str();
        var urls = entry.get("urls");
        if (sha == null || sha.length() != 64 || urls == null || urls.size() == 0) {
            return report(hasConsole, true, "The update file's entry for " + key + " needs a sha256 and at least one url.");
        }
        var exe = ExeInfo.fullPath();
        var download = exe + ".jr-download";
        var got = false;
        for (var i = 0; i < urls.size() && !got; i++) {
            var url = urls.at(i).str();
            if (url == null || !url.startsWith("https://")) continue;
            var progress = new Progress(guiMode, hasConsole, ExeInfo.baseNameNoExt() + " - Updating", "Downloading the update...");
            var ok = Http.downloadToFile(url, download, progress, false);
            progress.finish();
            var actual = ok ? Sha256.ofFile(download) : null;
            got = actual != null && AsciiStr.equalsIgnoreCase(actual, sha);
            if (!got) {
                Log.warn("update: " + url + (ok ? " did not match its sha256" : " could not be downloaded"));
                WinApi.deleteFileW(download);
            }
        }
        if (!got) return report(hasConsole, true, "Could not download a verified update. Nothing was changed.");
        var old = exe + REPLACED;
        WinApi.deleteFileW(old);
        if (WinApi.moveFileExW(exe, old, MOVEFILE_REPLACE_EXISTING) == 0) {
            WinApi.deleteFileW(download);
            return report(hasConsole, true, "Could not move the running exe aside (error " + WinApi.getLastError() + "). Nothing was changed.");
        }
        if (WinApi.moveFileExW(download, exe, MOVEFILE_REPLACE_EXISTING) == 0) {
            var err = WinApi.getLastError();
            WinApi.moveFileExW(old, exe, MOVEFILE_REPLACE_EXISTING);
            WinApi.deleteFileW(download);
            return report(hasConsole, true, "Could not move the update into place (error " + err + "). The old exe was restored.");
        }
        return report(hasConsole, false, "Updated: " + c.appVersion + " -> " + check.release.get("version").str()
                + ". The new version runs from the next launch.");
    }

    private static int report(boolean hasConsole, boolean error, String message) {
        status = error ? UpdateResult.ERROR : UpdateResult.UPDATED;
        said = message;
        if (error) {
            Log.error(message);
            Ui.error(hasConsole, "Update", message);
            return 1;
        }
        Log.info(message);
        Ui.info(hasConsole, "Update", message);
        return 0;
    }
}
