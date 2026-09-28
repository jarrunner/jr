package memlab.ffi;

import org.teavm.interop.Address;

import static memlab.ffi.Mem.M;

public final class F {
    private F() {}

    public static Address c(String s) { return M.c(s); }
    public static Address w(String s) { return M.w(s); }
    public static Address buf(int n) { return M.buf(n); }
    public static String str(Address p) { return M.str(p); }
    public static long mark() { return Arena.mark(); }
    public static void reset(long mark) { Arena.reset(mark); }
}
