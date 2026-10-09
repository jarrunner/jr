package jarrunner.jr;

import static jarrunner.jr.N.*;

/** QueryPerformanceCounter-based elapsed-microseconds timing, mirroring launcher.c's initTimer. */
public final class Timing {
    private Timing() {}

    private static long freq;
    private static long start;
    private static long startMicros;

    public static void init() {
        var addr = alloc(WinOffsets.LARGE_INTEGER.SIZE);
        WinApi.queryPerformanceFrequency(addr);
        freq = WinOffsets.LARGE_INTEGER.QuadPart(addr);
        WinApi.queryPerformanceCounter(addr);
        start = WinOffsets.LARGE_INTEGER.QuadPart(addr);
        startMicros = elapsedMicros();
    }

    public static long startMicros() {
        return startMicros;
    }

    public static long elapsedMicros() {
        var addr = alloc(WinOffsets.LARGE_INTEGER.SIZE);
        WinApi.queryPerformanceCounter(addr);
        var now = WinOffsets.LARGE_INTEGER.QuadPart(addr);
        return freq == 0 ? 0 : ((now - start) * 1_000_000L) / freq;
    }
}
