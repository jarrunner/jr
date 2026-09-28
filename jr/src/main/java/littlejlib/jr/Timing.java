package littlejlib.jr;

import static littlejlib.jr.N.*;

/** QueryPerformanceCounter-based elapsed-microseconds timing, mirroring launcher.c's initTimer. */
public final class Timing {
    private Timing() {}

    private static long freq;
    private static long start;
    private static long startMicros;

    public static void init() {
        var addr = longVar();
        WinApi.queryPerformanceFrequency(addr);
        freq = addr.getLong();
        WinApi.queryPerformanceCounter(addr);
        start = addr.getLong();
        startMicros = elapsedMicros();
    }

    public static long startMicros() {
        return startMicros;
    }

    public static long elapsedMicros() {
        var addr = longVar();
        WinApi.queryPerformanceCounter(addr);
        var now = addr.getLong();
        return freq == 0 ? 0 : ((now - start) * 1_000_000L) / freq;
    }
}
