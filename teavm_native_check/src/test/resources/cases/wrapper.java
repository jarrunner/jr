// expect: NC1-field NC1-return NC2-array
package t;

import static t.N.*;

class Wrapper {
    static Buf kept;

    static Buf escapes() { return memScoped(() -> Buf.of(8)); }

    static int fine() {
        return memScoped(() -> {
            var b = Buf.of(8);
            var many = new Buf[2];
            return b.getInt(4) + os(b.ptr());
        });
    }
}
