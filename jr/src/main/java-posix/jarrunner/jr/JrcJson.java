package jarrunner.jr;

/** The jrc-json form of the config (PRP-30), as the Windows JrcJson reads it: the maven plugin writes it and
 *  embeds it (PRP-36 on macOS), and it maps onto Config.applyKey so every launch path is unchanged. Unknown
 *  keys are ignored. The POSIX differences: values are quoted for PosixCmdLineBuilder's split (double quotes
 *  group), and a path may use $VAR or ${VAR} as well as %VAR%. */
public final class JrcJson {
    private JrcJson() {}

    /** True if the text starts with '{' after whitespace. */
    public static boolean looksLikeJson(String text) {
        var i = 0;
        while (i < text.length() && text.charAt(i) <= ' ') i++;
        return i < text.length() && text.charAt(i) == '{';
    }

    /** Parses and applies; returns null, or the parse error. */
    public static String load(String text, Config c) {
        var root = JsonReader.parse(text);
        if (root.isError()) return root.error();
        if (root.kind() != JsonValue.OBJECT) return "the config must be a JSON object";
        apply(root, c);
        c.jsonRoot = root;
        return null;
    }

    static void apply(JsonValue r, Config c) {
        text(c, "java.version", r.path("java", "version"));
        text(c, "java.min", r.path("java", "min"));
        text(c, "java.preferred", r.path("java", "preferred"));
        text(c, "java.max", r.path("java", "max"));
        text(c, "aot", r.get("aot"));
        text(c, "java.type", r.path("java", "type"));
        text(c, "java.home", r.path("java", "home"));
        text(c, "java.autoinstall", r.path("java", "autoinstall"));
        text(c, "jvm", r.path("jvm", "mode"));
        args(c, "vm.args", r.path("jvm", "vmArgs"));
        text(c, "java.args", r.path("jvm", "javaArgs"));
        args(c, "app.args", r.path("app", "args"));
        for (var k : new String[] {"name", "email", "issues", "url"}) text(c, "support." + k, r.path("support", k));
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
        // jar.sources, as on Windows: the first path whose file exists is run as it is; otherwise maven and url
        // entries are the download list RemoteJar tries in order. A missing path is passed on only when nothing
        // else is listed, so that java reports it.
        var sources = r.path("jar", "sources");
        String lastPath = null;
        for (var i = 0; sources != null && i < sources.size(); i++) {
            var s = sources.at(i);
            if (s.get("path") != null) {
                lastPath = resolve(str(s.get("path")));
                if (FileIo.exists(lastPath)) {
                    c.sources = "";
                    c.applyKey("java.args", "-jar " + quote(lastPath));
                    return;
                }
            } else {
                var maven = s.get("maven") != null;
                c.sources += (maven ? "m" : "u") + str(s.get(maven ? "maven" : "url")) + "\n";
            }
        }
        if (c.sources.isEmpty() && lastPath != null) c.applyKey("java.args", "-jar " + quote(lastPath));
    }

    /** A string, number or boolean value as the text a .jrc line would carry. */
    private static void text(Config c, String key, JsonValue v) {
        if (v == null) return;
        var t = v.str() != null ? v.str() : v.num() != null ? v.num() : v.bool() == 1 ? "true" : v.bool() == 0 ? "false" : null;
        if (t != null) c.applyKey(key, t);
    }

    /** An array of strings as one argument string, each element quoted only if it needs it. */
    private static void args(Config c, String key, JsonValue v) {
        if (v == null || v.kind() != JsonValue.ARRAY) return;
        var sb = new StringBuilder();
        for (var i = 0; i < v.size(); i++) {
            var s = v.at(i).str();
            if (s == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(s));
        }
        c.applyKey(key, sb.toString());
    }

    /** Quoted for PosixCmdLineBuilder's split: double quotes group whitespace. A value that itself contains a
     *  double quote cannot be expressed there; it is passed unquoted. */
    static String quote(String s) {
        if (s.isEmpty()) return "\"\"";
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) <= ' ') return s.indexOf('"') < 0 ? "\"" + s + "\"" : s;
        }
        return s;
    }

    private static String str(JsonValue v) {
        return v == null || v.str() == null ? "" : v.str();
    }

    /** %VAR%, $VAR and ${VAR} expanded; a leading ~/ is $HOME; a relative path is taken from the binary's own
     *  folder (inside an .app that is Contents/MacOS). */
    static String resolve(String path) {
        if (path.startsWith("~/")) path = "${HOME}" + path.substring(1);
        var sb = new StringBuilder();
        var i = 0;
        while (i < path.length()) {
            var c = path.charAt(i);
            int nameStart = -1, nameEnd = -1, next = -1;
            if (c == '%') {
                nameStart = i + 1;
                nameEnd = path.indexOf('%', nameStart);
                next = nameEnd + 1;
            } else if (c == '$' && i + 1 < path.length() && path.charAt(i + 1) == '{') {
                nameStart = i + 2;
                nameEnd = path.indexOf('}', nameStart);
                next = nameEnd + 1;
            } else if (c == '$') {
                nameStart = i + 1;
                nameEnd = nameStart;
                while (nameEnd < path.length() && isNameChar(path.charAt(nameEnd))) nameEnd++;
                next = nameEnd;
            }
            var val = nameEnd > nameStart ? Cstr.readEnv(path.substring(nameStart, nameEnd)) : null;
            if (val != null) {
                sb.append(val);
                i = next;
            } else {
                sb.append(c);
                i++;
            }
        }
        var p = sb.toString();
        if (p.startsWith("/")) return p;
        var exe = ExeInfo.fullPath();
        var slash = exe.lastIndexOf('/');
        return (slash < 0 ? "" : exe.substring(0, slash + 1)) + p;
    }

    private static boolean isNameChar(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
