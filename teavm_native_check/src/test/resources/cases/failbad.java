// expect: NC10-unchecked NC10-unchecked NC10-unchecked NC10-unchecked NC10-unchecked NC10-return NC2-generic
package t;

import java.util.Optional;
import org.teavm.interop.Address;
import static t.N.*;

class FailBad {
    static void unchecked() {
        var f = Os.getProc(NULL, NULL);
        Os.read(f, NULL, 0);
    }

    static void wrongValue() {
        var h = Os.create(1);
        if (h.toLong() == 0) return;      // create fails with INVALID, not NULL
        Os.read(h, NULL, 0);
    }

    static void direct() { Os.read(Os.getProc(NULL, NULL), NULL, 0); }

    static void oneBranch() {
        var f = Os.getProc(NULL, NULL);
        if (Os.read(NULL, NULL, 0) > 0 && f.toLong() != 0) Os.read(NULL, NULL, 0);
        Os.read(f, NULL, 0);
    }

    static Address notMarked() { return Os.getProc(NULL, NULL); }

    static Address boxed(Address p) {
        Optional<Address> o = Optional.of(p);
        return o.get();
    }

    static int ignores(@Nullable Address m) { return Os.read(m, NULL, 0); }
}
