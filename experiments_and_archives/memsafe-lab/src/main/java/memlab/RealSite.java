package memlab;

import memlab.ffi.Ffi;
import org.teavm.interop.Address;

import static memlab.Lab.report;

public final class RealSite {
    private RealSite() {}

    static final int SIZE = 36, LAST_WRITE = 20;

    static long oldLastWrite(String path, boolean gcBetween) {
        var buf = new byte[SIZE];
        var addr = Address.ofData(buf);
        if (C.getFileAttributesExA(gcBetween ? Old.cstrAfterGc(path) : Old.cstr(path), 0, addr) == 0) return -1;
        if (gcBetween) Gc.stormAndRefill();
        return addr.add(LAST_WRITE).getLong();
    }

    static long newLastWrite(String path, boolean gcBetween) {
        return Ffi.l(m -> {
            var data = m.buf(SIZE);
            if (C.getFileAttributesExA(m.c(path), 0, data) == 0) return -1;
            if (gcBetween) Gc.stormAndRefill();
            return data.add(LAST_WRITE).getLong();
        });
    }

    static void run(String path, boolean includeOld) {
        var truth = oldLastWrite(path, false);
        System.out.println("[real] " + path + " ftLastWriteTime=" + truth);
        report("real.new+gc", "" + truth, "" + newLastWrite(path, true));
        if (includeOld) report("real.old+gc", "" + truth, "" + oldLastWrite(path, true));
    }

    static long ticks() {
        return Ffi.l(m -> {
            var t = m.int64();
            C.queryPerformanceCounter(t);
            return t.getLong();
        });
    }

    static void bench(int n) {
        var freq = Ffi.l(m -> {
            var t = m.int64();
            C.queryPerformanceFrequency(t);
            return t.getLong();
        });
        var s = "C:\\Users\\someone\\.jbang\\cache\\jdks\\25\\bin\\java.exe";
        var sum = 0L;
        var t0 = ticks();
        for (var i = 0; i < n; i++) sum += C.strlen(Old.cstr(s));
        var t1 = ticks();
        for (var i = 0; i < n; i++) sum += Ffi.l(m -> C.strlen(m.c(s)));
        var t2 = ticks();
        for (var i = 0; i < n; i++) sum += Ffi.l(m -> C.strcmp(m.c(s), m.c(s)) + C.strlen(m.w(s)));
        var t3 = ticks();
        System.out.println("[bench] n=" + n + " heap Cstr.of: " + ns(t1 - t0, freq, n) + " ns/call, Ffi.c: "
                + ns(t2 - t1, freq, n) + " ns/call, Ffi 2xc+w: " + ns(t3 - t2, freq, n) + " ns/call (sum " + sum + ")");
    }

    static long ns(long ticks, long freq, int n) {
        return ticks * 1_000_000_000L / freq / n;
    }
}
