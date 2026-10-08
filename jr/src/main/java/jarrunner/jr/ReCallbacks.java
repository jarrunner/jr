package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/**
 * The Enum* callbacks resedit.c uses to read a target exe's existing resources - mirrors
 * reLangCb/reFirstNameCb/reMaxIdCb/reListLangCb/reListNameCb/reListTypeCb. Every callback here is
 * bound once per call via {@code Function.get}, matching jextract-teavm's documented pattern (see
 * jextract-teavm/README.md "Callbacks: C calling Java") - jextract itself typed the three Enum*
 * functions' callback parameters as plain Address rather than a generated Function subclass (this
 * target resolves ENUMRES*PROCW through the FARPROC branch, not the function-pointer one), so the
 * three Function subclasses below are hand-written instead of generated; their invoke() signatures
 * are exactly what the real callback ABI is, which is all Function.get checks against.
 *
 * None of this nests two calls of the SAME shape at once (list-resources nests Types -&gt; Names -&gt;
 * Langs, but each level is its own distinct callback method), so plain static fields are a safe and
 * much simpler accumulator than threading a native "param" pointer the way resedit.c does.
 */
@Unsafe("hands Java methods to EnumResource*W as C callbacks; the *Callback classes declare their C signatures")
public final class ReCallbacks {
    private ReCallbacks() {}

    abstract static class LangCallback extends Function {
        abstract int invoke(Address module, Address type, Address name, short lang, long lParam);
    }

    abstract static class NameCallback extends Function {
        abstract int invoke(Address module, Address type, Address name, long lParam);
    }

    abstract static class TypeCallback extends Function {
        abstract int invoke(Address module, Address type, long lParam);
    }

    // ---- EnumResourceLanguagesW: every language a (type, name) resource exists under ----

    private static short[] langBuf;
    private static int langCount;

    /** mirrors reGetLangs. Empty if module is the null handle (no existing resources at all, a
     *  target with no resource section yet) or the resource does not exist. */
    public static short[] getLangs(Address module, ResId type, ResId name) {
        if (module.toLong() == 0) {
            return new short[0];
        }
        langBuf = new short[16];
        langCount = 0;
        var cb = (Address) (Object) Function.get(LangCallback.class, ReCallbacks.class, "collectLang");
        WinApi.enumResourceLanguagesW(module, type.toAddress(), name.toAddress(), cb, 0L);
        var out = java.util.Arrays.copyOf(langBuf, langCount);
        langBuf = null;
        return out;
    }

    @SameThread("EnumResourceLanguagesW calls it before returning")
    static int collectLang(Address module, Address type, Address name, short lang, long lParam) {
        if (langCount < langBuf.length) {
            langBuf[langCount++] = lang;
        }
        return 1; // TRUE: keep enumerating
    }

    // ---- EnumResourceNamesW: the first name under a type (the main icon group) ----

    private static ResId firstNameResult;

    /** mirrors reFirstNameCb's use in reQueueIcon: the first RT_GROUP_ICON name, or null. */
    public static ResId firstName(Address module, ResId type) {
        if (module.toLong() == 0) {
            return null;
        }
        firstNameResult = null;
        var cb = (Address) (Object) Function.get(NameCallback.class, ReCallbacks.class, "captureFirstName");
        WinApi.enumResourceNamesW(module, type.toAddress(), cb, 0L);
        var result = firstNameResult;
        firstNameResult = null;
        return result;
    }

    @SameThread("EnumResourceNamesW calls it before returning")
    static int captureFirstName(Address module, Address type, Address name, long lParam) {
        firstNameResult = ResId.read(name);
        return 0; // FALSE: the first one is all we want
    }

    // ---- EnumResourceNamesW: the highest numeric name under a type (fresh icon image ids) ----

    private static int maxIdResult;

    /** mirrors reMaxIdCb's use in reQueueIcon: 0 if module is null or there are no numeric names. */
    public static int maxNumericName(Address module, ResId type) {
        if (module.toLong() == 0) {
            return 0;
        }
        maxIdResult = 0;
        var cb = (Address) (Object) Function.get(NameCallback.class, ReCallbacks.class, "trackMaxId");
        WinApi.enumResourceNamesW(module, type.toAddress(), cb, 0L);
        var result = maxIdResult;
        maxIdResult = 0;
        return result;
    }

    @SameThread("EnumResourceNamesW calls it before returning")
    static int trackMaxId(Address module, Address type, Address name, long lParam) {
        var id = ResId.read(name);
        if (id.isNumeric() && (id.id & 0xFFFF) > maxIdResult) {
            maxIdResult = id.id & 0xFFFF;
        }
        return 1; // TRUE: continue
    }

    // ---- -Xjr:list-resources: every (type, name, lang) triple, with its size ----

    private static StringBuilder listReport;
    private static int listCount;

    /** mirrors reList's EnumResourceTypesW/-Names/-Languages nesting. Returns the formatted lines;
     *  listCount() afterwards says how many, so the caller can append "(none)" when it is zero. */
    public static String listResources(Address module) {
        listReport = new StringBuilder();
        listCount = 0;
        var cb = (Address) (Object) Function.get(TypeCallback.class, ReCallbacks.class, "listType");
        WinApi.enumResourceTypesW(module, cb, 0L);
        var report = listReport.toString();
        listReport = null;
        return report;
    }

    public static int listCount() {
        return listCount;
    }

    @SameThread("EnumResourceTypesW calls it before returning")
    static int listType(Address module, Address type, long lParam) {
        var cb = (Address) (Object) Function.get(NameCallback.class, ReCallbacks.class, "listName");
        WinApi.enumResourceNamesW(module, type, cb, 0L);
        return 1;
    }

    @SameThread("EnumResourceNamesW calls it before returning")
    static int listName(Address module, Address type, Address name, long lParam) {
        var cb = (Address) (Object) Function.get(LangCallback.class, ReCallbacks.class, "listLang");
        WinApi.enumResourceLanguagesW(module, type, name, cb, 0L);
        return 1;
    }

    @SameThread("EnumResourceLanguagesW calls it before returning")
    static int listLang(Address module, Address type, Address name, short lang, long lParam) {
        var typeId = ResId.read(type);
        var nameId = ResId.read(name);
        var r = WinApi.findResourceExW(module, type, name, lang);
        var size = r.toLong() != 0 ? WinApi.sizeofResource(module, r) : 0;
        listReport.append("  ").append(padRight(typeId.describe(true), 12)).append(' ')
                .append(padRight(nameId.describe(false), 20)).append(" lang ")
                .append(padRight(String.valueOf(lang & 0xFFFF), 5)).append(' ')
                .append(padLeft(String.valueOf(size), 8)).append(" bytes\n");
        listCount++;
        return 1;
    }

    private static String padRight(String s, int width) {
        var sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String padLeft(String s, int width) {
        var sb = new StringBuilder();
        for (var i = s.length(); i < width; i++) {
            sb.append(' ');
        }
        return sb.append(s).toString();
    }
}
