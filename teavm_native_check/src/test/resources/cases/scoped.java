// expect: NC3-scoped NC3-scoped
package t;

import org.teavm.interop.Address;
import static t.N.*;

class ScopedCalls {
    static void outside() { os(bigBuffer(1 << 20)); }

    static void inside() { memScoped(() -> { os(bigBuffer(16)); }); }

    static Runnable later() { return () -> os(bigBuffer(16)); }
}
