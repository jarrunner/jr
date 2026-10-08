package t;

import org.teavm.interop.Address;

/** Stand-ins for generated bindings: open/close pairs as jextract_teavm writes them. */
public final class Os {
    @Acquires("close") public static native Address open(Address path);
    @Acquires("close") public static Address open(String path) { return open(N.NULL); }
    public static native int close(Address h);
    public static native int read(Address h, Address buf, int n);
    @Acquires("freeLibrary") @Fails("NULL") public static native Address loadLibrary(Address path);
    @Fails("NULL") public static native Address getProc(Address module, Address name);
    public static native int useMaybe(@Nullable Address m);
    @Fails("INVALID") public static native Address create(int how);
    public static native int freeLibrary(Address m);
    public static native Address getStdHandle(int n);
    public static final Address INVALID = Address.fromLong(-1);
}
