package pocapp.jr;

import org.teavm.interop.Address;

/** Off-heap bump allocator behind {@link N}: one malloc'd block, malloc'd chunks for anything that does not fit,
 *  reset to a mark when a memScoped ends. Memory here never moves and is never collected, which is the whole point:
 *  TeaVM's GC frees or moves any heap array known only through an Address (prp/18-prp.01). Freed memory is filled
 *  with 0xDD so a pointer kept past its scope reads garbage at once instead of working by luck. */
final class Arena {
    private Arena() {}

    static final int BLOCK = 64 * 1024;
    static long base;
    static int top;
    static long[] big = new long[16];
    static int bigN;

    static Address alloc(int n) {
        n = (n + 15) & ~15;
        if (base == 0) base = WinApi.malloc(BLOCK).toLong();
        if (top + n <= BLOCK) {
            var a = Address.fromLong(base + top);
            top += n;
            return a;
        }
        if (bigN == big.length) big = java.util.Arrays.copyOf(big, bigN * 2);
        var a = WinApi.malloc(n);
        big[bigN++] = a.toLong();
        return a;
    }

    static long mark() {
        return ((long) bigN << 32) | top;
    }

    static void reset(long mark) {
        var t = (int) mark;
        if (top > t) WinApi.memset(Address.fromLong(base + t), 0xDD, top - t);
        top = t;
        var b = (int) (mark >>> 32);
        while (bigN > b) WinApi.free(Address.fromLong(big[--bigN]));
    }
}
