package t;

import org.teavm.interop.Address;

public final class Api {
    @CType("struct _A") public static final class A {
        public static final int SIZE = 8;
        public static int x(@CType("struct _A") Address s) { return s.getInt(); }
        public static @CType("struct _B") Address inner(@Returned @CType("struct _A") Address s) { return s.add(4); }
    }

    @CType("struct _B") public static final class B {
        public static final int SIZE = 4;
    }

    public static native int useA(@CType("struct _A") Address a);
    public static native int useAny(Address p);
    @CType("struct _B") public static native Address makeB();
}
