// expect: NC4-raw
package t;

import org.teavm.interop.Address;

class Trust {
    static int vouched(Address p) { return Buf.wrap(p, 8).getInt(4); }

    @Unsafe("p comes from the OS with exactly 8 bytes")
    static int marked(Address p) { return Buf.wrap(p, 8).getInt(4); }

    static int owned() { return Buf.of(8).getInt(4); }
}
