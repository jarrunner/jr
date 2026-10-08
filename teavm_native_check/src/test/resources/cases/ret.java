// expect: NC1-return NC1-return NC1-return
package t;

import org.teavm.interop.Address;
import static t.N.*;

class Ret {
    static Address expression() { return memScoped(() -> alloc(4)); }

    static Address block() {
        return memScoped(() -> {
            var p = alloc(4);
            return p;
        });
    }

    static int nested() {
        return memScoped(() -> {
            var outer = alloc(4);
            Address inner = memScoped(() -> (alloc(8)));
            return os(outer) + os(inner);
        });
    }
}
