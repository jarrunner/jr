package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

/**
 * The pending -Xjr: resource-editing/signing options, mirroring resedit.h's ReStamp struct and
 * resedit.c's reParseOption/reHasAction/reHasEdits. See resedit.h's own top comment for the option
 * list; this is the Java side of prp/20-prp-teavm_port_catches_up_with_the_c_launcher.md item 6.
 */
public final class ReStamp {
    static final int MAX_ITEMS = 32; // RE_MAX_ITEMS in resedit.c

    public record VersionString(String name, String value) {}
    public record StringEntry(int id, String text) {}
    public record RawResource(String type, String name, String file) {}

    public String make = "";
    public String edit = "";
    public String listResources = "";
    public String icon = "";
    public String fileVersion = "";
    public String productVersion = "";
    public final List<VersionString> versionStrings = new ArrayList<>();
    public String manifest = "";
    public String executionLevel = "";
    public final List<StringEntry> strings = new ArrayList<>();
    public final List<RawResource> raws = new ArrayList<>();
    public String signPfx = "";
    public String signThumbprint = "";
    public String signTimestamp = "";

    /** Set when parseOption returns -1: the message for the user. */
    public String error;

    /** opt is one -Xjr: option with the "-Xjr:" prefix already removed. Returns 1 if it was a
     *  resource/signing option (this object is updated), 0 if it is not one of ours, -1 if it was
     *  ours but malformed (error is set) - mirrors reParseOption exactly, key by key. */
    public int parseOption(String opt) {
        var eq = opt.indexOf('=');
        var key = eq < 0 ? opt : opt.substring(0, eq);
        var value = eq < 0 ? "" : opt.substring(eq + 1);

        switch (AsciiStr.lower(key)) {
            case "make" -> { return simple(key, value, v -> make = v); }
            case "edit" -> { return simple(key, value, v -> edit = v); }
            case "list-resources" -> { return simple(key, value, v -> listResources = v); }
            case "icon" -> { return simple(key, value, v -> icon = v); }
            case "file-version" -> { return simple(key, value, v -> fileVersion = v); }
            case "product-version" -> { return simple(key, value, v -> productVersion = v); }
            case "manifest" -> { return simple(key, value, v -> manifest = v); }
            case "execution-level" -> {
                var r = simple(key, value, v -> executionLevel = v);
                if (r != 1) {
                    return r;
                }
                if (!AsciiStr.equalsIgnoreCase(value, "asInvoker") && !AsciiStr.equalsIgnoreCase(value, "highestAvailable")
                        && !AsciiStr.equalsIgnoreCase(value, "requireAdministrator")) {
                    error = "-Xjr:execution-level must be asInvoker, highestAvailable or requireAdministrator";
                    return -1;
                }
                return 1;
            }
            case "sign" -> { return simple(key, value, v -> signPfx = v); }
            case "sign.thumbprint" -> { return simple(key, value, v -> signThumbprint = v); }
            case "sign.timestamp" -> { return simple(key, value, v -> signTimestamp = v); }
            case "version" -> {
                if (value.isEmpty()) {
                    error = "-Xjr:version needs a value, e.g. -Xjr:version=1.2.3.4";
                    return -1;
                }
                fileVersion = value;
                productVersion = value;
                return 1;
            }
            default -> { }
        }

        if (AsciiStr.startsWithIgnoreCase(key, "version.") && key.length() > 8) {
            if (versionStrings.size() >= MAX_ITEMS) {
                error = "Too many -Xjr:version.* options";
                return -1;
            }
            versionStrings.add(new VersionString(key.substring(8), value));
            return 1;
        }

        if (AsciiStr.startsWithIgnoreCase(key, "string.")) {
            var idText = key.substring(7);
            var id = isAllDigits(idText) ? Atoi.parse(idText) : -1;
            if (id < 0 || id > 65535) {
                error = "-Xjr:string.<id> needs a numeric id from 0 to 65535, e.g. -Xjr:string.101=Hello";
                return -1;
            }
            if (strings.size() >= MAX_ITEMS) {
                error = "Too many -Xjr:string.* options";
                return -1;
            }
            strings.add(new StringEntry(id, value));
            return 1;
        }

        if (AsciiStr.startsWithIgnoreCase(key, "resource.")) {
            var rest = key.substring(9);
            var dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1 || value.isEmpty()) {
                error = "Use -Xjr:resource.<type>.<name>=<file>, e.g. -Xjr:resource.RCDATA.CONFIG=app.json";
                return -1;
            }
            if (raws.size() >= MAX_ITEMS) {
                error = "Too many -Xjr:resource.* options";
                return -1;
            }
            raws.add(new RawResource(rest.substring(0, dot), rest.substring(dot + 1), value));
            return 1;
        }

        return 0;
    }

    private interface Setter {
        void set(String value);
    }

    private int simple(String key, String value, Setter setter) {
        if (value.isEmpty()) {
            error = "-Xjr:" + key + " needs a value: -Xjr:" + key + "=...";
            return -1;
        }
        setter.set(value);
        return 1;
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (var i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    public boolean hasResourceEdits() {
        return !icon.isEmpty() || !fileVersion.isEmpty() || !productVersion.isEmpty() || !versionStrings.isEmpty()
                || !manifest.isEmpty() || !executionLevel.isEmpty() || !strings.isEmpty() || !raws.isEmpty();
    }

    public boolean hasSigning() {
        return !signPfx.isEmpty() || !signThumbprint.isEmpty();
    }

    public boolean hasAction() {
        return !make.isEmpty() || !edit.isEmpty() || !listResources.isEmpty();
    }

    public boolean hasEdits() {
        return hasResourceEdits() || hasSigning() || !signTimestamp.isEmpty();
    }
}
