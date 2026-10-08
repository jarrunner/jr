// expect: NC1-field NC1-field NC1-assign NC1-assign
package t;

import org.teavm.interop.Address;
import static t.N.*;

class Assign {
    static Address kept;

    static class Holder { Address p; }

    static void run() {
        var h = new Holder();
        memScoped(() -> { kept = alloc(4); });
        memScoped(() -> { h.p = alloc(4); });
        memScoped(() -> {
            var local = new Holder();
            var q = alloc(4);
            q = alloc(8);
            os(q);
        });
    }
}
