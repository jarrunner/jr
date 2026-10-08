// expect: NC6-type NC6-type NC6-type NC6-type NC6-type
package t;

import org.teavm.interop.Address;
import static t.N.*;

class Typed {
    static final int B_SIZE = Api.B.SIZE;

    static int alias() { return Api.useA(alloc(B_SIZE)); }

    static int wrong() {
        var b = Api.makeB();
        return Api.useA(b);
    }

    static int wrongAlloc() { return Api.A.x(alloc(Api.B.SIZE)); }

    static int wrongDirect() { return Api.useA(Api.makeB()); }

    static int nested() {
        var a = alloc(Api.A.SIZE);
        return Api.useA(Api.A.inner(a));
    }

    static int right() {
        var a = alloc(Api.A.SIZE);
        var inLambda = memScoped(() -> Api.useA(a));
        return Api.useA(a) + Api.A.x(a) + Api.useAny(Api.makeB()) + inLambda;
    }

    static int unknown(boolean f) {
        var p = alloc(Api.A.SIZE);
        if (f) p = Api.makeB();
        return Api.useA(p) + Api.useA(alloc(16)) + Api.useA(N.NULL);
    }

    static int param(@CType("struct _B") Address b) { return Api.useA(f(b)) + Api.useAny(b); }

    static Address f(@Returned Address x) { return x; }
}
