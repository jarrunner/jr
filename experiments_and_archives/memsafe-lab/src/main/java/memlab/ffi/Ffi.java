package memlab.ffi;

import static memlab.ffi.Mem.M;

public final class Ffi {
    private Ffi() {}

    public interface IntBody { int run(Mem m); }
    public interface LongBody { long run(Mem m); }
    public interface BoolBody { boolean run(Mem m); }
    public interface StrBody { String run(Mem m); }
    public interface VoidBody { void run(Mem m); }

    public static int i(IntBody b) {
        var mark = Arena.mark();
        try { return b.run(M); } finally { Arena.reset(mark); }
    }

    public static long l(LongBody b) {
        var mark = Arena.mark();
        try { return b.run(M); } finally { Arena.reset(mark); }
    }

    public static boolean ok(BoolBody b) {
        var mark = Arena.mark();
        try { return b.run(M); } finally { Arena.reset(mark); }
    }

    public static String s(StrBody b) {
        var mark = Arena.mark();
        try { return b.run(M); } finally { Arena.reset(mark); }
    }

    public static void run(VoidBody b) {
        var mark = Arena.mark();
        try { b.run(M); } finally { Arena.reset(mark); }
    }

    public static void poison(boolean on) { Arena.poison = on; }
    public static void checkLeaks() {
        if (Arena.top != 0 || Arena.bigN != 0) throw new IllegalStateException("native memory still held: " + live());
    }
    public static String live() { return "top=" + Arena.top + " bigLive=" + Arena.bigN; }
    public static String stats() {
        return "arena: block=" + Arena.BLOCK + " top=" + Arena.top + " peak=" + Arena.peak
                + " bigLive=" + Arena.bigN + " bigAllocsEver=" + Arena.bigAllocs;
    }
}
