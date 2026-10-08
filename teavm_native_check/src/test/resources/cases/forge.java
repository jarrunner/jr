// expect: NC4-forge NC4-forge NC4-forge NC7-return
package t;

import org.teavm.interop.Address;

class Forge {
    static final Address CONSTANT = Address.fromLong(-1L);

    static Address fromNumber(long v) { return Address.fromLong(v); }

    static Object toObject(Address p) { return (Object) p; }

    static Address fromObject(Object o) { return (Address) o; }

    @Unsafe("v is an OS handle value")
    static Address marked(long v) { return Address.fromLong(v); }
}
