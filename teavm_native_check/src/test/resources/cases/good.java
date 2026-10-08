// expect: none
package t;

import org.teavm.interop.Address;
import static t.N.*;

class Good {
    static final Address INVALID = Address.fromLong(-1L);
    static final Address ALSO_NULL = N.NULL;
    static final int BASE = 0x80000000;
    static final Address HKLM = Address.fromInt(BASE + 2);
    @Handle static Address window;

    record Found(@Handle Address data, int size) {}

    static int readInt() {
        return memScoped(() -> {
            var p = alloc(4);
            os(p);
            return intAt(p);
        });
    }

    @Unsafe("the window handle comes back from the OS as a number")
    static void openWindow() {
        memScoped(() -> { window = Address.fromLong(os(alloc(8))); });
    }

    static void loop(java.util.List<String> lines) {
        for (var s : lines) memScoped(() -> {
            var buf = bigBuffer(s.length());
            lines.forEach(x -> os(buf));
        });
    }

    @Scoped
    static Address passOn(int n) { return bigBuffer(n); }

    @Unsafe("walks a table by offset")
    static int raw(Address p) { return p.add(4).getInt() + (int) p.toLong(); }

    @Unsafe("whole class reads a packed structure")
    static class Packed {
        int first(Address p) { return p.getInt(); }
    }

    @Unsafe("os returns a handle value")
    static Address fromOs() { return Address.fromLong(os(NULL)); }

    static void sleepOutside() throws InterruptedException {
        memScoped(() -> os(alloc(4)));
        Thread.sleep(1);
    }
}
