package jarrunner.jr;

import org.teavm.interop.Address;

import java.util.ArrayList;
import java.util.List;

import static jarrunner.jr.N.*;

/**
 * The pending resource update: a list of (type, name, language) entries to write, or to delete
 * when data is the null Address - mirrors resedit.c's ReEntry/ReEntries/reAdd/reQueueReplace/reFind.
 * Existing resources are read from the target first (loaded as a data file, see reApplyResources),
 * the module is released, and only then is the file opened for update - EndUpdateResource cannot
 * rewrite a file that is mapped.
 */
public final class ReEntries {
    private static final int MAX_ENTRIES = 256; // RE_MAX_ENTRIES in resedit.c

    /** Language for a resource the target does not already have. An existing resource keeps its
     *  own language, so it is replaced rather than joined by a second copy - RE_DEFAULT_LANG. */
    public static final short DEFAULT_LANG = 0x0409; // MAKELANGID(LANG_ENGLISH, SUBLANG_ENGLISH_US)

    public record Entry(ResId type, ResId name, short lang, Address data, int size) {
        boolean isDelete() {
            return data.toLong() == 0;
        }
    }

    private final List<Entry> list = new ArrayList<>();

    /** Set by queueReplace() on success: the language the replacement should use. */
    public short lastLang;

    public List<Entry> entries() {
        return list;
    }

    /** mirrors reAdd. data may be the null Address, meaning "delete this resource". */
    public boolean add(ResId type, ResId name, short lang, Address data, int size) {
        if (list.size() >= MAX_ENTRIES) {
            return false;
        }
        list.add(new Entry(type, name, lang, data, size));
        return true;
    }

    /** Queues deletion of every language of (type, name) already in the target, and sets lastLang
     *  to the language the replacement should use: the existing one if there was one, else
     *  DEFAULT_LANG. Returns false only if the entry list is full - mirrors reQueueReplace. */
    public boolean queueReplace(Address module, ResId type, ResId name) {
        var langs = ReCallbacks.getLangs(module, type, name);
        for (var lang : langs) {
            if (!add(type, name, lang, NULL, 0)) {
                return false;
            }
        }
        lastLang = langs.length > 0 ? langs[0] : DEFAULT_LANG;
        return true;
    }

    public record Found(Address data, int size) {}

    /** Existing resource bytes, valid until the module is freed - mirrors reFind. Null if module is
     *  the null handle (no resource section) or the resource does not exist. */
    public static Found find(Address module, ResId type, ResId name, short lang) {
        if (module.toLong() == 0) {
            return null;
        }
        var r = WinApi.findResourceExW(module, type.toAddress(), name.toAddress(), lang);
        if (r.toLong() == 0) {
            return null;
        }
        var g = WinApi.loadResource(module, r);
        if (g.toLong() == 0) {
            return null;
        }
        return new Found(WinApi.lockResource(g), WinApi.sizeofResource(module, r));
    }
}
