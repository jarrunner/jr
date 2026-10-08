// expect: NC4-raw NC4-raw NC4-raw NC4-reason
package t;

import org.teavm.interop.Address;
import static t.N.*;

class Raw {
    static int read(Address p) { return p.getInt(); }

    static void write(Address p) { p.add(4).putByte((byte) 1); }

    static long number(Address p) { return p.toLong(); }

    @Unsafe
    static int noReason(Address p) { return p.getInt(); }

    static int viaHelper(Address p) { return intAt(p); }
}
