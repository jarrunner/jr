package jarrunner.jr;

/** PRP-31: the Java majors an app accepts, worked out from java.version / java.min / java.preferred /
 *  java.max and aot. The rules and their reasons are in prp/31-prp.01. jr applies them at launch, and
 *  JrcCheck at bake time, so an author meets a contradiction before any user does. */
public final class JavaRange {
    static final int DEFAULT = 25, AOT_MIN = 25, OLDEST = 8;

    int min, preferred, max; // 0 = open
    String error;            // non-null: the declaration contradicts itself, nothing is launched
    String note;             // non-null: a declared version was raised, e.g. for AOT

    private JavaRange() {}

    static JavaRange of(Config c) {
        var r = new JavaRange();
        r.error = c.javaRangeError != null ? c.javaRangeError
                : c.javaVersionSet && c.javaKeysSet ? "Set java.version or java.min/preferred/max, not both." : null;
        if (r.error != null) {
            return r;
        }
        var dMin = c.javaMin;
        var dPref = c.javaPreferred;
        var dMax = c.javaMax;
        r.preferred = dPref > 0 ? dPref : dMin > 0 ? dMin : dMax > 0 && dMax < DEFAULT ? dMax : DEFAULT;
        r.min = dMin > 0 ? dMin : dPref;
        r.max = dMax;
        if (c.enableAOT != 0) {
            if (r.max > 0 && r.max < AOT_MIN) {
                r.error = "java.max=" + r.max + " is below " + AOT_MIN + ", which AOT needs. Set aot=false to run on Java " + r.max + ".";
                return r;
            }
            if (r.min < AOT_MIN || r.preferred < AOT_MIN) {
                if (dMin > 0 || dPref > 0) {
                    r.note = "AOT is on and needs Java " + AOT_MIN + "; the declared Java " + Math.max(dMin, dPref)
                            + " is raised to " + AOT_MIN + ". Set aot=false to run on it.";
                }
                r.min = Math.max(r.min, AOT_MIN);
                r.preferred = Math.max(r.preferred, AOT_MIN);
            }
        }
        if (r.min > 0 && r.min < OLDEST) {
            r.error = "Java " + r.min + " is older than jr can launch (Java " + OLDEST + " or newer).";
        } else if (r.max > 0 && r.min > r.max) {
            r.error = "java.min=" + r.min + " is above java.max=" + r.max + ".";
        } else if (r.preferred < r.min || r.max > 0 && r.preferred > r.max) {
            r.error = "java.preferred=" + r.preferred + " is outside " + r.describe() + ".";
        }
        return r;
    }

    boolean accepts(int major) {
        return major > 0 && major >= min && (max == 0 || major <= max);
    }

    String describe() {
        return "min " + (min > 0 ? String.valueOf(min) : "none") + ", preferred " + preferred
                + ", max " + (max > 0 ? String.valueOf(max) : "none");
    }

    /** java.version: "25" and "25+" both mean min 25 and preferred 25; "[21,25]" means min 21, max 25. */
    static void declareVersion(Config c, String value) {
        c.javaVersionSet = true;
        Log.info("java.version=" + value);
        var v = value.strip();
        if (v.startsWith("[") && v.endsWith("]") && v.indexOf(',') > 0) {
            var comma = v.indexOf(',');
            c.javaMin = declared(c, "java.version", v.substring(1, comma));
            c.javaMax = declared(c, "java.version", v.substring(comma + 1, v.length() - 1));
        } else if (!v.isEmpty()) {
            c.javaMin = declared(c, "java.version", v);
            c.javaPreferred = c.javaMin;
        }
    }

    /** One declared major, or 0 with Config.javaRangeError set when it is not one. */
    static int declared(Config c, String key, String value) {
        if (!key.equals("java.version")) {
            Log.info(key + "=" + value);
        }
        var n = major(value);
        if (n < 0) {
            c.javaRangeError = key + "=" + value + " is not a Java major version (write a number such as 25).";
            return 0;
        }
        return n;
    }

    /** "25", "25+", "1.8" -> the major; anything else (17.0.2, abc, 0) -> -1. */
    static int major(String s) {
        if (s == null) {
            return -1;
        }
        var v = s.strip();
        if (v.endsWith("+")) {
            v = v.substring(0, v.length() - 1);
        }
        if (v.startsWith("1.") && v.length() > 2) {
            v = v.substring(2);
        }
        if (v.isEmpty() || v.length() > 3) {
            return -1;
        }
        for (var i = 0; i < v.length(); i++) {
            if (v.charAt(i) < '0' || v.charAt(i) > '9') {
                return -1;
            }
        }
        var n = Atoi.parse(v);
        return n > 0 ? n : -1;
    }
}
