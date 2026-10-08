package t;

import java.util.function.Supplier;
import org.teavm.interop.Address;

public final class N {
    public static final Address NULL = Address.fromInt(0);
    static long base;

    public static <T> T memScoped(Supplier<T> body) { return body.get(); }
    public static void memScoped(Runnable body) { body.run(); }

    public static Address alloc(int n) {
        var a = Address.fromLong(base);
        Address.fillZero(a, n);
        return a;
    }

    @Scoped
    public static Address bigBuffer(int n) { return alloc(n); }

    public static int intAt(Address p) { return p.getInt(); }
    public static native int os(Address p);
}
