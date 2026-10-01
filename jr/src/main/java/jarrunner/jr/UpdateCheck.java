package jarrunner.jr;

/** Reads the update json named by the config's update.url and decides whether a newer release
 *  exists (PRP-30). Version strings are labels only: the releases list is newest first, jr finds
 *  its own app.version by exact match, and the channel names the release it should be on. */
public final class UpdateCheck {
    public static final int CURRENT = 0, NEWER = 10, ERROR = 1;

    int status = ERROR;
    String message;
    JsonValue release;   // the channel's release, when NEWER

    private UpdateCheck() {}

    public static UpdateCheck run(Config c) {
        var u = new UpdateCheck();
        if (c.updateUrl.isEmpty() || c.appVersion.isEmpty()) {
            return u.done(ERROR, "This exe has no update source: its config needs update.url and app.version.");
        }
        Log.info("update check: " + c.updateUrl);
        var raw = Http.getToBuffer(c.updateUrl, 4 * 1024 * 1024);
        if (raw == null) return u.done(ERROR, "Could not fetch the update file:\n" + c.updateUrl);
        var root = JsonReader.parse(Utf8.decode(raw));
        if (root.isError()) return u.done(ERROR, "The update file is not valid JSON (" + root.error() + "):\n" + c.updateUrl);
        var format = root.get("format");
        if (format == null || !"1".equals(format.num())) {
            return u.done(ERROR, "The update file's format is not one this jr understands (it reads format 1).");
        }
        var channel = c.updateChannel.isEmpty() ? "stable" : c.updateChannel;
        var target = root.path("channels", channel);
        if (target == null || target.str() == null) return u.done(ERROR, "The update file has no channel named " + channel + ".");
        var releases = root.get("releases");
        var own = indexOf(releases, c.appVersion);
        var newest = indexOf(releases, target.str());
        if (newest < 0) return u.done(ERROR, "The update file names " + target.str() + " on " + channel + " but does not list it.");
        var retracted = own < 0 ? null : releases.at(own).get("retracted");
        var note = retracted != null && retracted.str() != null ? "\nThis version was withdrawn: " + retracted.str() : "";
        if (own == newest) return u.done(CURRENT, "Up to date: " + c.appVersion + " (" + channel + ")." + note);
        if (own >= 0 && own < newest) {
            return u.done(CURRENT, "This build, " + c.appVersion + ", is newer than " + channel + " (" + target.str() + ")." + note);
        }
        u.release = releases.at(newest);
        var notes = u.release.get("notes");
        return u.done(NEWER, "A newer version is available: " + c.appVersion + " -> " + target.str() + " (" + channel + ")"
                + (notes != null && notes.str() != null ? "\n" + notes.str() : "") + note);
    }

    private static int indexOf(JsonValue releases, String version) {
        for (var i = 0; releases != null && i < releases.size(); i++) {
            var v = releases.at(i).get("version");
            if (v != null && version.equals(v.str())) return i;
        }
        return -1;
    }

    private UpdateCheck done(int status, String message) {
        this.status = status;
        this.message = message;
        return this;
    }
}
