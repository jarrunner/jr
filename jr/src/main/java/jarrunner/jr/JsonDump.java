package jarrunner.jr;

/** -Xjr:json-dump=&lt;file&gt;: reads a JSON file with jr's own reader and prints it as canonical
 *  JSON, or the parse error. The JVM side prints the same form, so the two can be compared on
 *  shared sample files (PRP-30). Exit 0 parsed, 1 unreadable or invalid. */
public final class JsonDump {
    private JsonDump() {}

    public static int run(String path) {
        var raw = FileIo.readAll(path);
        if (raw == null) {
            System.err.println("cannot read " + path);
            return 1;
        }
        var v = JsonReader.parse(Utf8.decode(raw));
        if (v.isError()) {
            System.err.println(path + ": " + v.error());
            return 1;
        }
        System.out.println(JsonWriter.write(v));
        return 0;
    }
}
