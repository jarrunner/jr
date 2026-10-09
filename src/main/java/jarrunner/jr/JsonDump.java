package jarrunner.jr;

/** -Xjr:json-dump=&lt;file&gt;: reads a JSON file with jr's own reader and prints it as canonical
 *  JSON, or the parse error. The JVM side prints the same form, so the two can be compared on
 *  shared sample files (PRP-30). Exit 0 parsed, 1 unreadable or invalid. */
public final class JsonDump {
    private JsonDump() {}

    public static int run(String path) {
        var raw = FileIo.readAll(path);
        if (raw == null) {
            Stderr.println("cannot read " + path);
            return 1;
        }
        var v = JsonReader.parse(raw);
        if (v.isError()) {
            Stderr.println(path + ": " + v.error());
            return 1;
        }
        Stderr.out(JsonWriter.write(v) + "\n");
        return 0;
    }

    /** -Xjr:check-config=&lt;file&gt;: the same check a bake runs, without baking. Exit 0 clean or
     *  warnings only, 1 errors. */
    public static int check(String path) {
        var raw = FileIo.readAll(path);
        if (raw == null) {
            Stderr.println("cannot read " + path);
            return 1;
        }
        if (!JrcJson.looksLikeJson(raw)) {
            Stderr.println(path + ": not a jrc-json");
            return 1;
        }
        var report = JrcCheck.check(raw);
        Stderr.out(report.isEmpty() ? path + ": ok\n" : report);
        return JrcCheck.hasErrors(report) ? 1 : 0;
    }
}
