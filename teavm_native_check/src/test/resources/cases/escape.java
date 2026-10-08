// expect: NC7-return NC7-return NC7-return NC7-store NC7-pass NC7-scoped NC7-scoped NC7-override
package t;

import org.teavm.interop.Address;
import static t.N.*;

class Escape {
    @Handle static Address kept;

    static Address back(Address p) { return p; }

    @Unsafe("offset arithmetic in a test")
    static Address offset(Address p) {
        var q = p.add(4);
        return q;
    }

    static Address viaReturned(Address a) { return Api.A.inner(a); }

    static Address passThrough(@Returned Address a) { return Api.A.inner(a); }

    static void store(Address p) { kept = p; }

    static void keep(@Escapes Address p) { kept = p; }

    static void forward(Address p) { keep(p); }

    static void forwardOk(@Escapes Address p) { keep(p); }

    static void scoped() { memScoped(() -> { var b = alloc(8); keep(b); }); }

    static void scopedField() { memScoped(() -> { keep(Api.A.inner(alloc(Api.A.SIZE))); }); }

    static int scopedRead() { return memScoped(() -> Api.useA(Api.A.inner(alloc(Api.A.SIZE)) == N.NULL ? N.NULL : alloc(8))); }

    static void longLived() { keep(alloc(8)); }

    static int use(Address p) { return os(p) + Api.A.x(p); }

    static Address fresh() { return alloc(4); }

    interface Sink { void take(Address p); }

    static class Bad implements Sink {
        public void take(@Escapes Address p) { kept = p; }
    }

    static class Fine implements Sink {
        public void take(Address p) { os(p); }
    }
}
