// expect: none
package t;

import org.teavm.interop.Address;
import static t.N.*;

class ResGood {
    @Handle static Address kept;

    static void closed() {
        var h = Os.open("a");
        Os.read(h, NULL, 0);
        Os.close(h);
    }

    static int failedOpen() {
        var h = Os.open("a");
        if (h.toLong() == 0 || h == Os.INVALID) {
            return -1;
        }
        var n = Os.read(h, NULL, 0);
        Os.close(h);
        return n;
    }

    static void notEqual() {
        var h = Os.open("a");
        if (h != Os.INVALID) {
            Os.close(h);
        }
    }

    static void isNull() {
        var h = Os.open("a");
        if (h.isNull()) return;
        Os.close(h);
    }

    static void finallyCloses() throws Fail {
        var h = Os.open("a");
        try {
            if (Os.read(h, NULL, 0) < 0) throw new Fail("short read");
            mayFail();
        } finally {
            Os.close(h);
        }
    }

    static void closeThenThrow() throws Fail {
        var h = Os.open("a");
        if (Os.read(h, NULL, 0) < 0) {
            Os.close(h);
            throw new Fail("short read");
        }
        Os.close(h);
    }

    static void caught() {
        try {
            mayFail();
        } catch (Fail e) {
            return;
        }
    }

    static void mayFail() throws Fail {
        if (Os.read(NULL, NULL, 0) < 0) throw new Fail("x");
    }

    @Acquires("close") static Address opener() {
        var h = Os.open("a");
        if (h.toLong() == 0) return h;
        return h;
    }

    static void viaOpener() { Os.close(opener()); }

    static void stored() { kept = Os.open("a"); }

    static void handedOn() { closeLater(Os.open("a")); }

    static void closeLater(@Owns("close") Address h) { Os.close(h); }

    static void moved() {
        var h = Os.open("a");
        var g = h;
        Os.close(g);
    }

    static int inScope() {
        return memScoped(() -> {
            var h = Os.open("a");
            if (h.toLong() == 0) return -1;
            var n = Os.read(h, alloc(8), 8);
            Os.close(h);
            return n;
        });
    }

    static void loop() {
        for (var i = 0; i < 3; i++) {
            var h = Os.open("a");
            if (h.toLong() == 0) continue;
            Os.close(h);
        }
    }

    static void whileTrue() {
        while (true) {
            var h = Os.open("a");
            if (h.toLong() == 0) break;
            Os.close(h);
        }
    }

    static void module() {
        var m = Os.loadLibrary(NULL);
        if (m.toLong() == 0) return;
        try {
            Os.read(m, NULL, 0);
        } finally {
            Os.freeLibrary(m);
        }
    }

    static void notAResource() {
        var h = Os.getStdHandle(1);
        Os.read(h, NULL, 0);
    }

    static void handed() {
        var m = Os.loadLibrary(NULL);
        if (m.toLong() == 0) return;
        handOver(m); // stays loaded
    }

    static void catchCloses() throws Fail {
        var h = Os.open("a");
        try {
            mayFail();
        } catch (Fail e) {
            Os.close(h);
            throw e;
        }
        Os.close(h);
    }

    static void labeled() {
        outer:
        for (var i = 0; i < 2; i++) {
            var h = Os.open("a");
            for (var j = 0; j < 2; j++) {
                if (j == 1) {
                    Os.close(h);
                    continue outer;
                }
            }
            Os.close(h);
        }
    }

    static int switched(int k) {
        var h = Os.open("a");
        return switch (k) {
            case 0 -> {
                Os.close(h);
                yield 1;
            }
            default -> {
                Os.close(h);
                yield 2;
            }
        };
    }
}
