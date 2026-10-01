package jarrunner.jr;

/** The jrc-json form of the config (PRP-30): the same settings as a key=value .jrc, as JSON, so
 *  the maven plugin can write it and the JVM side can read it with the same classes. Detected by
 *  its first character; mapped onto Config.applyKey so every launch path is unchanged. Unknown
 *  keys are ignored here and reported by JrcCheck before baking. */
public final class JrcJson {
    private JrcJson() {}

    /** True if the text (a byte string, as FileIo.readAll returns it) starts with '{', after an
     *  optional UTF-8 BOM and whitespace. */
    public static boolean looksLikeJson(String bytes) {
        var i = bytes.startsWith("ï»¿") ? 3 : 0;
        while (i < bytes.length() && bytes.charAt(i) <= ' ') i++;
        return i < bytes.length() && bytes.charAt(i) == '{';
    }

    /** Parses and applies; returns null, or the parse error. */
    public static String load(String bytes, Config c) {
        var root = JsonReader.parse(Utf8.decode(bytes));
        if (root.isError()) return root.error();
        if (root.kind() != JsonValue.OBJECT) return "the config must be a JSON object";
        apply(root, c);
        return null;
    }

    static void apply(JsonValue r, Config c) {
        text(c, "java.version", r.path("java", "version"));
        text(c, "java.type", r.path("java", "type"));
        text(c, "java.home", r.path("java", "home"));
        text(c, "java.autoinstall", r.path("java", "autoinstall"));
        text(c, "jvm", r.path("jvm", "mode"));
        args(c, "vm.args", r.path("jvm", "vmArgs"));
        text(c, "java.args", r.path("jvm", "javaArgs"));
        args(c, "app.args", r.path("app", "args"));
        text(c, "aot", r.get("aot"));
        text(c, "log.file", r.path("log", "file"));
        text(c, "log.level", r.path("log", "level"));
        text(c, "log.overwrite", r.path("log", "overwrite"));
        c.appId = str(r.path("app", "id"));
        c.appVersion = str(r.path("app", "version"));
        c.updateUrl = str(r.path("update", "url"));
        c.updateChannel = str(r.path("update", "channel"));
        text(c, "run.sha256", r.path("jar", "sha256"));
        text(c, "run.crc32", r.path("jar", "crc32"));
        text(c, "run.verify", r.path("jar", "verify"));
        var first = r.path("jar", "sources") == null ? null : r.path("jar", "sources").at(0);
        if (first != null) {
            text(c, "run.maven", first.get("maven"));
            text(c, "run.url", first.get("url"));
            var path = first.get("path");
            if (path != null && path.str() != null) {
                c.applyKey("java.args", "-jar " + WinQuote.quote(resolve(path.str())));
            }
        }
    }

    /** A string, number or boolean value as the text a .jrc line would carry. */
    private static void text(Config c, String key, JsonValue v) {
        if (v == null) return;
        var t = v.str() != null ? v.str() : v.num() != null ? v.num() : v.bool() == 1 ? "true" : v.bool() == 0 ? "false" : null;
        if (t != null) c.applyKey(key, t);
    }

    /** An array of strings as one command-line string, each element quoted only if it needs it. */
    private static void args(Config c, String key, JsonValue v) {
        if (v == null || v.kind() != JsonValue.ARRAY) return;
        var sb = new StringBuilder();
        for (var i = 0; i < v.size(); i++) {
            var s = v.at(i).str();
            if (s == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(WinQuote.quote(s));
        }
        c.applyKey(key, sb.toString());
    }

    private static String str(JsonValue v) {
        return v == null || v.str() == null ? "" : v.str();
    }

    /** %VAR% expanded; a relative path taken from the exe's own folder. */
    static String resolve(String path) {
        var sb = new StringBuilder();
        var i = 0;
        while (i < path.length()) {
            var c = path.charAt(i);
            var end = c == '%' ? path.indexOf('%', i + 1) : -1;
            var val = end > i + 1 ? Cstr.readEnv(path.substring(i + 1, end)) : null;
            if (val != null) {
                sb.append(val);
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        var p = sb.toString();
        var absolute = p.startsWith("\\") || p.startsWith("/") || (p.length() > 1 && p.charAt(1) == ':');
        if (absolute) return p;
        var exe = ExeInfo.fullPath();
        var slash = Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/'));
        return (slash < 0 ? "" : exe.substring(0, slash + 1)) + p;
    }
}
