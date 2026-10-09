package jarrunner.jr;

/** Bare-minimum sanity check of a jrc-json before it is baked into an exe (PRP-30): valid JSON,
 *  the keys jr acts on have the right types and values, and there is something to run. Errors
 *  refuse the bake; unknown keys are only warnings, so a config written for a newer jr still bakes.
 *  Messages are built by two helpers, never by concatenation: TeaVM's C backend inlines a
 *  StringBuilder sequence per "a" + b site, and the first version of this class cost ~25 KB. */
public final class JrcCheck {
    private static final String SECTIONS = " app java jar jvm aot log update support localDev ";
    private static final String KEYS =
            " app.id app.name app.version app.args java.version java.min java.preferred java.max java.type java.home java.autoinstall"
            + " jar.sha256 jar.crc32 jar.verify jar.sources jvm.mode jvm.vmArgs jvm.javaArgs log.file log.level log.overwrite"
            + " update.url update.channel support.name support.email support.issues support.url ";
    private static final String[] TEXT = {"app.id", "app.name", "app.version", "java.home", "jar.sha256",
            "jvm.javaArgs", "log.file", "log.level", "update.url", "update.channel", "support.name", "support.email", "support.issues", "support.url"};
    private static final String[] BOOL = {"java.autoinstall", "log.overwrite"};
    private static final String[] LIST = {"app.args", "jvm.vmArgs"};

    private final StringBuilder errors = new StringBuilder();
    private final StringBuilder warnings = new StringBuilder();

    private JrcCheck() {}

    /** Returns the report: "" when clean. Lines starting "error:" mean the bake must be refused. */
    public static String check(String text) {
        var c = new JrcCheck();
        var r = JsonReader.parse(text);
        if (r.isError()) {
            c.error("not valid JSON: ", r.error());
        } else if (r.kind() != JsonValue.OBJECT) {
            c.error("the config", " must be a JSON object");
        } else {
            c.keys(r);
            c.values(r);
        }
        return c.errors.append(c.warnings).toString();
    }

    public static boolean hasErrors(String report) {
        return report.startsWith("error:") || report.contains("\nerror:");
    }

    private void keys(JsonValue r) {
        var full = new StringBuilder();
        for (var i = 0; i < r.size(); i++) {
            var k = r.keyAt(i);
            var v = r.at(i);
            if (!listed(SECTIONS, k, null)) {
                warn(k);
            } else if (k.equals("aot") || k.equals("localDev")) {
                bool(k, v);
            } else if (v.kind() != JsonValue.OBJECT) {
                error(k, " must be an object");
            } else {
                for (var j = 0; j < v.size(); j++) {
                    if (!listed(KEYS, k, v.keyAt(j))) {
                        full.setLength(0);
                        warn(full.append(k).append('.').append(v.keyAt(j)).toString());
                    }
                }
            }
        }
    }

    private void values(JsonValue r) {
        for (var k : TEXT) if (at(r, k) != null && at(r, k).str() == null) error(k, " must be text");
        for (var k : BOOL) bool(k, at(r, k));
        for (var k : LIST) list(k, at(r, k));
        javaRange(r);
        oneOf("java.type", at(r, "java.type"), "jre", "jdk", " must be \"jre\" or \"jdk\"");
        oneOf("jvm.mode", at(r, "jvm.mode"), "dll", "exe", " must be \"dll\" or \"exe\"");
        var verify = at(r, "jar.verify");
        if (verify != null && !"crc32".equals(verify.str()) && !"sha256".equals(verify.str()) && !"none".equals(verify.str())) {
            error("jar.verify", " must be \"crc32\", \"sha256\" or \"none\"");
        }
        var crc = at(r, "jar.crc32");
        if (crc != null && (crc.str() == null || crc.str().length() != 8 || !hex(crc.str()))) error("jar.crc32", " must be 8 hex characters");
        https("update.url", at(r, "update.url"));
        var sha = at(r, "jar.sha256");
        if (sha != null && sha.str() != null && !hex64(sha.str())) error("jar.sha256", " must be 64 hex characters");
        var src = at(r, "jar.sources");
        if (src == null) {
            if (at(r, "jvm.javaArgs") == null) error("nothing to run", ": give jar.sources or jvm.javaArgs");
        } else if (src.kind() != JsonValue.ARRAY || src.size() == 0) {
            error("jar.sources", " must be a non-empty list");
        } else {
            for (var i = 0; i < src.size(); i++) source(src.at(i), sha);
        }
    }

    private void source(JsonValue s, JsonValue sha) {
        var maven = s.get("maven");
        var url = s.get("url");
        var n = (maven != null ? 1 : 0) + (url != null ? 1 : 0) + (s.get("path") != null ? 1 : 0);
        if (s.kind() != JsonValue.OBJECT || n != 1) {
            error("jar.sources", ": each entry must have exactly one of maven, url or path");
        } else if (s.at(0).str() == null) {
            error("jar.sources", ": maven, url and path must be text");
        } else {
            https("jar.sources url", url);
            if (maven != null && !gav(maven.str())) error("jar.sources maven", " must be group:artifact:version");
            if ((maven != null || url != null) && sha == null) error("jar.sha256", " is required to download the jar");
        }
    }

    private static boolean listed(String list, String a, String b) {
        if (a.isEmpty() || (b != null && b.isEmpty())) return false;
        var at = 0;
        while ((at = list.indexOf(a, at)) >= 0) {
            var end = at + a.length();
            var ok = list.charAt(at - 1) == ' ' && (b == null ? list.charAt(end) == ' '
                    : list.charAt(end) == '.' && list.startsWith(b, end + 1) && list.charAt(end + 1 + b.length()) == ' ');
            if (ok) return true;
            at = end;
        }
        return false;
    }

    private static JsonValue at(JsonValue r, String dotted) {
        var dot = dotted.indexOf('.');
        return dot < 0 ? r.get(dotted) : r.path(dotted.substring(0, dot), dotted.substring(dot + 1));
    }

    private void bool(String k, JsonValue v) { if (v != null && v.bool() < 0) error(k, " must be true or false"); }

    private void list(String k, JsonValue v) {
        if (v == null) return;
        var ok = v.kind() == JsonValue.ARRAY;
        for (var i = 0; ok && i < v.size(); i++) ok = v.at(i).str() != null;
        if (!ok) error(k, " must be a list of text values");
    }

    private void oneOf(String k, JsonValue v, String a, String b, String msg) {
        if (v != null && !a.equals(v.str()) && !b.equals(v.str())) error(k, msg);
    }

    private void https(String k, JsonValue v) {
        if (v != null && (v.str() == null || !v.str().startsWith("https://"))) error(k, " must be an https:// address");
    }

    /** PRP-31: the same version rules jr applies at launch, so a contradiction is refused at bake time. */
    private void javaRange(JsonValue r) {
        var c = new Config();
        JrcJson.applyJava(r, c);
        var range = JavaRange.of(c);
        if (range.error != null) error("java: ", range.error);
        else if (range.note != null) warnings.append("warning: ").append(range.note).append('\n');
    }

    private static boolean hex64(String s) {
        return s.length() == 64 && hex(s);
    }

    private static boolean hex(String s) {
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private static boolean gav(String s) {
        var a = s.indexOf(':');
        var b = a < 0 ? -1 : s.indexOf(':', a + 1);
        return a > 0 && b > a + 1 && b < s.length() - 1 && s.indexOf(':', b + 1) < 0;
    }

    private void error(String what, String msg) { errors.append("error: ").append(what).append(msg).append('\n'); }

    private void warn(String key) { warnings.append("warning: unknown key '").append(key).append("' (ignored by this jr)\n"); }
}
