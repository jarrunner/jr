package littlejlib.jr;

import org.teavm.interop.Address;

import static littlejlib.jr.N.*;

/** A Win32 resource type or name: either a small numeric id (MAKEINTRESOURCE/IS_INTRESOURCE) or a
 *  wide-string name - mirrors resedit.c's reSetKey/reTypeOf/reNameOf (a WORD id plus a WCHAR[64] is
 *  how the C struct stores this same union; a small class does the same job here). Both directions
 *  - built in Java to pass to a WinAPI call, and read back out of an Enum* callback's Address
 *  parameter - go through this one type. */
public final class ResId {
    static final int MAX_NAME = 64; // RE_NAME_LEN in resedit.c

    public final int id;      // valid only when name == null
    public final String name; // null for a numeric id

    private ResId(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public static ResId of(int id) {
        return new ResId(id, null);
    }

    public static ResId of(String name) {
        return new ResId(0, name);
    }

    /** A generated RT_* constant, e.g. WinApi.RT_ICON - these are MAKEINTRESOURCE(n) macros, so
     *  jextract-teavm typed them Address (a pointer-typed macro), holding exactly the same "small
     *  integer wearing a pointer's clothes" value toAddress() produces for a numeric id. */
    public static ResId of(Address makeIntResourceConstant) {
        return of((int) makeIntResourceConstant.toLong());
    }

    public boolean isNumeric() {
        return name == null;
    }

    /** MAKEINTRESOURCEW for a numeric id, or a real wide-string pointer - what a WinAPI call takes
     *  for an LPCWSTR type/name parameter. */
    public Address toAddress() {
        return name == null ? Address.fromLong(id & 0xFFFFL) : wcstr(name);
    }

    /** Reads a type/name Address exactly as an Enum* callback receives it: IS_INTRESOURCE tests
     *  whether the top 48 bits are zero (a real pointer never has that shape), and if so the low
     *  16 bits are the id; otherwise it is a genuine wide-string pointer. */
    public static ResId read(Address p) {
        var v = p.toLong();
        return (v >>> 16) == 0 ? of((int) v) : of(wstring(p, MAX_NAME));
    }

    /** Numeric type ids resedit.c's reDescribeId prints by name (RT_ICON -> "ICON", etc). Index is
     *  the resource type id; a gap (unused ids, e.g. 13) is null. */
    private static final String[] TYPE_NAMES = {
        null, "CURSOR", "BITMAP", "ICON", "MENU", "DIALOG", "STRING", "FONTDIR", "FONT", "ACCELERATOR",
        "RCDATA", "MESSAGETABLE", "GROUP_CURSOR", null, "GROUP_ICON", null, "VERSION", "DLGINCLUDE", null,
        "PLUGPLAY", "VXD", "ANICURSOR", "ANIICON", "HTML", "MANIFEST",
    };

    /** For -Xjr:list-resources: the well-known name for a numeric type id, else the bare number;
     *  a named resource prints as its name. Mirrors reDescribeId. */
    public String describe(boolean isType) {
        if (name != null) {
            return name;
        }
        var n = id & 0xFFFF;
        if (isType && n < TYPE_NAMES.length && TYPE_NAMES[n] != null) {
            return TYPE_NAMES[n];
        }
        return String.valueOf(n);
    }

    /** Numeric -> itself; a few well-known type names -> their ids; anything else -> an upper-cased
     *  name (resource names are case-insensitive and the resource compiler stores them upper-cased)
     *  - mirrors reParseResId, used by -Xjr:resource.&lt;type&gt;.&lt;name&gt;=&lt;file&gt;. */
    public static ResId parse(String text, boolean isType) {
        if (isAllDigits(text)) {
            return of(Integer.parseInt(text));
        }
        if (isType) {
            if (AsciiStr.equalsIgnoreCase(text, "RCDATA")) return of(WinApi.RT_RCDATA);
            if (AsciiStr.equalsIgnoreCase(text, "HTML")) return of(WinApi.RT_HTML);
            if (AsciiStr.equalsIgnoreCase(text, "MANIFEST")) return of(WinApi.RT_MANIFEST);
        } // (the overload above resolves these Address-typed RT_* constants)
        return of(upperAscii(text));
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

    private static String upperAscii(String s) {
        var chars = s.toCharArray();
        for (var i = 0; i < chars.length; i++) {
            if (chars[i] >= 'a' && chars[i] <= 'z') {
                chars[i] -= 32;
            }
        }
        return new String(chars);
    }
}
