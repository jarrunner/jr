package t;

import org.teavm.interop.Address;

public final class Buf {
    final Address base;
    final int size;

    Buf(Address base, int size) {
        this.base = base;
        this.size = size;
    }

    public static Buf of(int size) { return new Buf(N.alloc(size), size); }

    public static Buf wrap(@Returned Address base, int size) { return new Buf(base, size); }

    public int getInt(int off) { return base.add(off).getInt(); }

    public Address ptr() { return base; }
}
