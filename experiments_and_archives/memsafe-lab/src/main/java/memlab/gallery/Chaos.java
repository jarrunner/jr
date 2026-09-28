package memlab.gallery;

import memlab.Gc;

public final class Chaos {
    private Chaos() {}

    static boolean on;

    static void gc() {
        if (on) Gc.stormAndRefill();
    }
}
