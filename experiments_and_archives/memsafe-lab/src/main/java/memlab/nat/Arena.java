package memlab.nat;

import memlab.C;
import org.teavm.interop.Address;

final class Arena {
    private Arena() {}

    static final int BLOCK = 64 * 1024;
    static final int POISON = 0xDD;
    static boolean poison = true;
    static long base;
    static int top;
    static long[] big = new long[16];
    static int bigN;
    static int peak, bigAllocs;

    static Address alloc(int n) {
        n = (n + 15) & ~15;
        if (base == 0) base = C.malloc(BLOCK).toLong();
        if (top + n <= BLOCK) {
            var a = Address.fromLong(base + top);
            top += n;
            if (top > peak) peak = top;
            return a;
        }
        if (bigN == big.length) big = java.util.Arrays.copyOf(big, bigN * 2);
        var a = C.malloc(n);
        big[bigN++] = a.toLong();
        bigAllocs++;
        return a;
    }

    static long mark() {
        return ((long) bigN << 32) | top;
    }

    static void reset(long mark) {
        var t = (int) mark;
        if (poison && top > t) C.memset(Address.fromLong(base + t), POISON, top - t);
        top = t;
        var b = (int) (mark >>> 32);
        while (bigN > b) C.free(Address.fromLong(big[--bigN]));
    }
}
