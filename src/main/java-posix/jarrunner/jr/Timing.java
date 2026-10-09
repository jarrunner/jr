package jarrunner.jr;

import static jarrunner.jr.N.*;

/** clock_gettime(CLOCK_MONOTONIC)-based elapsed-microseconds timing - POSIX twin of the Windows
 *  QueryPerformanceCounter-based Timing. */
public final class Timing {
    private Timing() {}

    private static long startNanos;
    private static long startMicros;

    public static void init() {
        startNanos = nowNanos();
        startMicros = elapsedMicros();
    }

    public static long startMicros() {
        return startMicros;
    }

    public static long elapsedMicros() {
        return (nowNanos() - startNanos) / 1000;
    }

    private static long nowNanos() {
        var ts = alloc(PosixOffsets.timespec.SIZE);
        PosixApi.clockGettime(PosixApi.CLOCK_MONOTONIC, ts);
        return PosixOffsets.timespec.tv_sec(ts) * 1_000_000_000L + PosixOffsets.timespec.tv_nsec(ts);
    }
}
