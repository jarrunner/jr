package jarrunner.jr;

/** Tiny targeted JSON field extraction for the two Foojay Disco API responses JavaInstall reads -
 *  not a general parser. Pure Java
 *  string logic, no native interop needed at all. */
public final class Json {
    private Json() {}

    /** Finds `"key":"..."` (keyMarker is the literal up to and including the opening quote, e.g.
     *  {@code "\"id\":\""}) and returns the value up to the next quote, or null if not found. */
    public static String extractString(String json, String keyMarker) {
        var start = json.indexOf(keyMarker);
        if (start < 0) {
            return null;
        }
        start += keyMarker.length();
        var end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }
}
