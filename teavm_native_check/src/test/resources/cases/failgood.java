// expect: none
package t;

import org.teavm.interop.Address;
import static t.N.*;

class FailGood {
    static int nullTest() {
        var m = Os.loadLibrary(NULL);
        if (m.toLong() == 0) return -1;
        var f = Os.getProc(m, NULL);
        var r = f.isNull() ? -1 : Os.read(f, NULL, 0);
        Os.freeLibrary(m);
        return r;
    }

    static void invalidTest() {
        var h = Os.create(1);
        if (h == Os.INVALID) return;
        Os.read(h, NULL, 0);
    }

    static void both() {
        var h = Os.create(1);
        if (h.toLong() == 0 || h == Os.INVALID) return;
        Os.read(h, NULL, 0);
    }

    static void notEqual() {
        var m = Os.loadLibrary(NULL);
        if (m.toLong() != 0) {
            Os.read(m, NULL, 0);
            Os.freeLibrary(m);
        }
    }

    static int nullable() {
        var m = Os.loadLibrary(NULL);
        var r = Os.useMaybe(m);
        if (m.toLong() != 0) Os.freeLibrary(m);
        return r;
    }

    @Fails("NULL") static Address passOn() { return Os.getProc(NULL, NULL); }

    static int handles(@Nullable Address m) {
        if (m.toLong() == 0) return 0;
        return Os.read(m, NULL, 0);
    }
}
