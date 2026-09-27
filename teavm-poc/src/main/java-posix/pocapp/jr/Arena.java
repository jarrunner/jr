package pocapp.jr;

import org.teavm.interop.Address;

/** POSIX twin of the Windows Arena - identical bump-allocator logic, PosixApi.malloc/free/memset
 *  in place of WinApi's. See the Windows Arena.java for the full rationale (prp/18-prp.01). */
final class Arena {
    private Arena() {}

    static final int BLOCK = 64 * 1024;
    static long base;
    static int top;
    static long[] big = new long[16];
    static int bigN;

    static Address alloc(int n) {
        n = (n + 15) & ~15;
        if (base == 0) base = PosixApi.malloc(BLOCK).toLong();
        if (top + n <= BLOCK) {
            var a = Address.fromLong(base + top);
            top += n;
            return a;
        }
        if (bigN == big.length) big = java.util.Arrays.copyOf(big, bigN * 2);
        var a = PosixApi.malloc(n);
        big[bigN++] = a.toLong();
        return a;
    }

    static long mark() {
        return ((long) bigN << 32) | top;
    }

    static void reset(long mark) {
        var t = (int) mark;
        if (top > t) PosixApi.memset(Address.fromLong(base + t), 0xDD, top - t);
        top = t;
        var b = (int) (mark >>> 32);
        while (bigN > b) PosixApi.free(Address.fromLong(big[--bigN]));
    }
}
