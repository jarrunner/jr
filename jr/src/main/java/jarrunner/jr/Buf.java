package jarrunner.jr;

import org.teavm.interop.Address;

/** Native memory that knows its size (PRP-35 phase 3). In a checks build (build-win.ps1 -Checks) every read, write and
 *  slice is checked against that size and an overrun stops the program at once; in a release build Checks.ON is the
 *  constant false, javac drops the checks and what remains is the plain access. The OS gets the bare pointer, at the
 *  call, through ptr(). The checker treats a Buf like an Address: it is not kept in a field, returned out of a scope
 *  or put in an array. */
public final class Buf {
    final Address base;
    final int size;

    private Buf(Address base, int size) {
        this.base = base;
        this.size = size;
    }

    /** Zeroed memory from N.alloc, which lives until the innermost memScoped ends. */
    public static Buf alloc(int size) {
        return new Buf(N.alloc(size), size);
    }

    /** Memory the caller vouches holds size bytes (an array the OS returned, a header read from a file). Every later
     *  check trusts that number, so only {@code @Unsafe} code may call this (teavm_native_check, trust=). */
    public static Buf wrap(@Returned Address base, int size) {
        return new Buf(base, size);
    }

    public int size() { return size; }

    /** The bare pointer, for handing to the OS. */
    public Address ptr() { return base; }

    public Buf from(int off) {
        check(off, 0);
        return new Buf(base.add(off), size - off);
    }

    public Buf slice(int off, int len) {
        check(off, len);
        return new Buf(base.add(off), len);
    }

    public byte getByte(int off) { check(off, 1); return base.add(off).getByte(); }
    public short getShort(int off) { check(off, 2); return base.add(off).getShort(); }
    public char getChar(int off) { check(off, 2); return base.add(off).getChar(); }
    public int getInt(int off) { check(off, 4); return base.add(off).getInt(); }
    public long getLong(int off) { check(off, 8); return base.add(off).getLong(); }
    public float getFloat(int off) { check(off, 4); return base.add(off).getFloat(); }
    public double getDouble(int off) { check(off, 8); return base.add(off).getDouble(); }
    public Address getAddress(int off) { check(off, Address.sizeOf()); return base.add(off).getAddress(); }

    public void putByte(int off, byte v) { check(off, 1); base.add(off).putByte(v); }
    public void putShort(int off, short v) { check(off, 2); base.add(off).putShort(v); }
    public void putChar(int off, char v) { check(off, 2); base.add(off).putChar(v); }
    public void putInt(int off, int v) { check(off, 4); base.add(off).putInt(v); }
    public void putLong(int off, long v) { check(off, 8); base.add(off).putLong(v); }
    public void putFloat(int off, float v) { check(off, 4); base.add(off).putFloat(v); }
    public void putDouble(int off, double v) { check(off, 8); base.add(off).putDouble(v); }
    public void putAddress(int off, Address v) { check(off, Address.sizeOf()); base.add(off).putAddress(v); }

    private void check(int off, int width) {
        if (Checks.ON && (off < 0 || width < 0 || off > size - width)) {
            var msg = "native buffer overrun: " + width + " bytes at offset " + off + " of a " + size + "-byte buffer";
            if (!failing) { // TeaVM's crash handler prints only the stack, so say what happened first
                failing = true;
                Stderr.println("jr: " + msg);
            }
            throw new IndexOutOfBoundsException(msg);
        }
    }

    private static boolean failing;
}
