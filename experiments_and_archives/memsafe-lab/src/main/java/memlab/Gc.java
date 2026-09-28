package memlab;

public final class Gc {
    private Gc() {}

    static Object sink;

    public static void storm() {
        for (var i = 0; i < 4000; i++) sink = new byte[4096];
        sink = null;
        System.gc();
    }

    public static void refill() {
        for (var i = 0; i < 4000; i++) {
            var b = new byte[64];
            for (var j = 0; j < b.length; j++) b[j] = 'X';
            sink = b;
        }
    }

    public static void stormAndRefill() {
        storm();
        refill();
    }
}
