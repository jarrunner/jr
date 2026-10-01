package jarrunner.jr;

import java.util.ArrayList;

/** One parsed JSON value. Objects keep their keys in order as two parallel lists (no HashMap: the
 *  files jr reads are small, and a map costs exe size). Numbers stay as their source text, so a
 *  fraction or exponent a future field might carry is accepted without float parsing. */
public final class JsonValue {
    public static final int OBJECT = 1, ARRAY = 2, STRING = 3, NUMBER = 4, TRUE = 5, FALSE = 6, NULL = 7, ERROR = 8;

    final int kind;
    final String text;
    final ArrayList<String> keys;
    final ArrayList<JsonValue> items;

    JsonValue(int kind, String text) {
        this.kind = kind;
        this.text = text;
        this.keys = kind == OBJECT ? new ArrayList<>() : null;
        this.items = kind == OBJECT || kind == ARRAY ? new ArrayList<>() : null;
    }

    public int kind() { return kind; }
    public boolean isError() { return kind == ERROR; }
    public String error() { return kind == ERROR ? text : null; }
    public int size() { return items == null ? 0 : items.size(); }
    public JsonValue at(int i) { return items == null || i < 0 || i >= items.size() ? null : items.get(i); }
    public String keyAt(int i) { return keys == null || i < 0 || i >= keys.size() ? null : keys.get(i); }

    /** The member named key, or null. The last one wins if a key repeats. */
    public JsonValue get(String key) {
        if (keys == null) {
            return null;
        }
        for (var i = keys.size() - 1; i >= 0; i--) {
            if (keys.get(i).equals(key)) {
                return items.get(i);
            }
        }
        return null;
    }

    /** get(a).get(b)..., null as soon as a step is missing. */
    public JsonValue path(String... keys) {
        var v = this;
        for (var k : keys) {
            v = v == null ? null : v.get(k);
        }
        return v;
    }

    /** The string value, or null for anything that is not a JSON string. */
    public String str() { return kind == STRING ? text : null; }

    /** The number's source text (e.g. "1", "2.5", "1e9"), or null. */
    public String num() { return kind == NUMBER ? text : null; }

    /** 1 for true, 0 for false, -1 for anything else. */
    public int bool() { return kind == TRUE ? 1 : kind == FALSE ? 0 : -1; }

    void put(String key, JsonValue v) {
        keys.add(key);
        items.add(v);
    }
}
