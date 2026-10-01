package jarrunner.jr.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Is a newer release of this app out? Reads the same update file as {@code <exe> -Xjr:update-check}
 *  and decides by the same rules: the releases list is newest first, this build finds its own
 *  app.version by exact match, the channel names the release it should be on, and version strings
 *  are never parsed or compared.
 *  <p>
 *  Nothing here runs on its own and nothing is printed unless the app asks. A CLI typically calls
 *  {@link #inBackground} at startup and prints {@link #notice} on stderr if there is one; a window
 *  app shows the same Result in its own way. Installing stays jr's job: {@code <exe> -Xjr:update}. */
public final class UpdateCheck {

    public enum Status {
        /** Up to date, or this build is newer than its channel. */
        CURRENT,
        /** The channel is on a newer release. */
        NEWER,
        /** Not started by a jr exe, or its jrc-json has no update.url / app.version. */
        NOT_CONFIGURED,
        /** The update file could not be fetched or read; message says why. */
        ERROR
    }

    /** latest: the channel's version. retracted: why this build's own version was withdrawn, or null. */
    public record Result(Status status, String current, String latest, String channel, String notes,
                         String retracted, String message, Instant checkedAt) {
        public boolean newerAvailable() {
            return status == Status.NEWER;
        }
    }

    private UpdateCheck() {}

    /** Checks now, over the network, with what jr passed to this app. */
    public static Result check() {
        var url = JrApp.updateUrl().orElse(null);
        var version = JrApp.appVersion().orElse(null);
        if (url == null || version == null) return notConfigured();
        return check(url, version, JrApp.updateChannel());
    }

    /** Checks now against an explicit update file, version and channel. */
    public static Result check(String updateUrl, String version, String channel) {
        try {
            return decide(fetch(updateUrl), version, channel);
        } catch (IOException | RuntimeException e) {
            return error(version, channel, "could not read the update file " + updateUrl + ": " + e.getMessage());
        }
    }

    /** At most one network check per interval for this app: a result younger than the interval, for
     *  the same update url, version and channel, is returned from the cache under
     *  ~/.jr/update-check/. Errors are not cached, so an offline start does not hide the next check. */
    public static Result checkAtMostEvery(Duration interval) {
        var url = JrApp.updateUrl().orElse(null);
        var version = JrApp.appVersion().orElse(null);
        if (url == null || version == null) return notConfigured();
        return checkAtMostEvery(interval, cacheFile(JrApp.appId().orElse(JrApp.exeName().orElse("app"))),
                url, version, JrApp.updateChannel());
    }

    static Result checkAtMostEvery(Duration interval, Path cache, String url, String version, String channel) {
        var cached = readCache(cache, url, version, channel);
        if (cached != null && cached.checkedAt().plus(interval).isAfter(Instant.now())) return cached;
        var fresh = check(url, version, channel);
        if (fresh.status() != Status.ERROR) writeCache(cache, url, fresh);
        return fresh;
    }

    /** checkAtMostEvery on a daemon thread, so startup never waits on the network; onResult runs on
     *  that thread, and is not called when the app is not configured for updates. */
    public static void inBackground(Duration interval, Consumer<Result> onResult) {
        var t = new Thread(() -> {
            try {
                var r = checkAtMostEvery(interval);
                if (r.status() != Status.NOT_CONFIGURED) onResult.accept(r);
            } catch (RuntimeException ignored) {
                // an update check must never take the app down
            }
        }, "jr-update-check");
        t.setDaemon(true);
        t.start();
    }

    /** One line for the user when a newer release exists or this build was withdrawn, else null:
     *  "demo 1.1 is available (this is 1.0): run demo -Xjr:update". */
    public static String notice(Result r) {
        var name = JrApp.exeName().orElse("this app");
        if (r.status() == Status.NEWER) {
            return name + " " + r.latest() + " is available (this is " + r.current() + "): run " + name + " -Xjr:update"
                   + (r.retracted() != null ? " - this version was withdrawn: " + r.retracted() : "");
        }
        if (r.retracted() != null) return name + " " + r.current() + " was withdrawn: " + r.retracted();
        return null;
    }

    /** The decision, from the update file's text. Same rules and same cases as jr's UpdateCheck. */
    static Result decide(String updateJson, String version, String channel) {
        Object root;
        try {
            root = Json.parse(updateJson);
        } catch (IllegalArgumentException e) {
            return error(version, channel, "the update file is not valid JSON (" + e.getMessage() + ")");
        }
        if (!(Json.path(root, "format") instanceof Json.Num n && n.text().equals("1"))) {
            return error(version, channel, "the update file's format is not 1");
        }
        var target = Json.str(Json.path(root, "channels", channel));
        if (target == null) return error(version, channel, "the update file has no channel named " + channel);
        var releases = Json.path(root, "releases") instanceof List<?> l ? l : List.of();
        var own = indexOf(releases, version);
        var newest = indexOf(releases, target);
        if (newest < 0) return error(version, channel, "the update file names " + target + " on " + channel + " but does not list it");
        var retracted = own < 0 ? null : Json.str(Json.path(releases.get(own), "retracted"));
        var now = Instant.now();
        if (own == newest) {
            return new Result(Status.CURRENT, version, target, channel, null, retracted, "up to date: " + version + " (" + channel + ")", now);
        }
        if (own >= 0 && own < newest) {
            return new Result(Status.CURRENT, version, target, channel, null, retracted,
                    "this build, " + version + ", is newer than " + channel + " (" + target + ")", now);
        }
        var notes = Json.str(Json.path(releases.get(newest), "notes"));
        return new Result(Status.NEWER, version, target, channel, notes, retracted,
                "a newer version is available: " + version + " -> " + target + " (" + channel + ")", now);
    }

    private static int indexOf(List<?> releases, String version) {
        for (var i = 0; i < releases.size(); i++) {
            if (version.equals(Json.str(Json.path(releases.get(i), "version")))) return i;
        }
        return -1;
    }

    private static String fetch(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("update.url must be https");
        // NORMAL follows https -> https redirects: a GitHub releases/latest/download url is one.
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        try {
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    static Path cacheFile(String appId) {
        var safe = appId.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("user.home"), ".jr", "update-check", safe + ".json");
    }

    @SuppressWarnings("unchecked")
    private static Result readCache(Path file, String url, String version, String channel) {
        try {
            if (!Files.isRegularFile(file)) return null;
            var m = (Map<String, Object>) Json.parse(Files.readString(file));
            if (!url.equals(m.get("url")) || !version.equals(m.get("current")) || !channel.equals(m.get("channel"))) return null;
            return new Result(Status.valueOf((String) m.get("status")), version, (String) m.get("latest"), channel,
                    (String) m.get("notes"), (String) m.get("retracted"), (String) m.get("message"),
                    Instant.parse((String) m.get("checkedAt")));
        } catch (IOException | RuntimeException e) {
            return null;   // a damaged cache is simply a missing one
        }
    }

    private static void writeCache(Path file, String url, Result r) {
        var b = new StringBuilder("{");
        field(b, "url", url).append(',');
        field(b, "current", r.current()).append(',');
        field(b, "channel", r.channel()).append(',');
        field(b, "status", r.status().name()).append(',');
        field(b, "latest", r.latest()).append(',');
        field(b, "notes", r.notes()).append(',');
        field(b, "retracted", r.retracted()).append(',');
        field(b, "message", r.message()).append(',');
        field(b, "checkedAt", r.checkedAt().toString()).append('}');
        try {
            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, b);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // no cache only means the next start checks again
        }
    }

    private static StringBuilder field(StringBuilder b, String key, String value) {
        b.append('"').append(key).append("\":");
        if (value == null) return b.append("null");
        b.append('"');
        for (var c : value.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"');
    }

    private static Result notConfigured() {
        return new Result(Status.NOT_CONFIGURED, JrApp.appVersion().orElse(null), null, JrApp.updateChannel(), null, null,
                "not started by a jr exe with update.url and app.version", Instant.now());
    }

    private static Result error(String version, String channel, String message) {
        return new Result(Status.ERROR, version, null, channel, null, null, message, Instant.now());
    }
}
