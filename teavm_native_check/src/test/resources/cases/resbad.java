// expect: NC8-leak NC8-leak NC8-leak NC8-leak NC8-leak NC8-leak NC8-leak NC8-lost NC8-lost NC8-overwrite NC8-acquires NC8-throws NC8-leak NC8-leak
package t;

import org.teavm.interop.Address;
import static t.N.*;

class ResBad {
    static void neverClosed() {
        var h = Os.open("a");
        Os.read(h, NULL, 0);
    }

    static int earlyReturn() {
        var h = Os.open("a");
        if (Os.read(h, NULL, 0) < 0) return -1;
        Os.close(h);
        return 0;
    }

    static void secondCheckLeaks() {
        var h = Os.open("a");
        if (h.toLong() == 0 || Os.read(h, NULL, 0) < 0) {
            return;
        }
        Os.close(h);
    }

    static void throwsOpen() throws Fail {
        var h = Os.open("a");
        if (Os.read(h, NULL, 0) < 0) throw new Fail("short");
        Os.close(h);
    }

    static void calleeThrows() throws Fail {
        var h = Os.open("a");
        mayFail();
        Os.close(h);
    }

    static void mayFail() throws Fail {
        if (Os.read(NULL, NULL, 0) < 0) throw new Fail("x");
    }

    static void inLoop() {
        for (var i = 0; i < 3; i++) {
            var h = Os.open("a");
            if (Os.read(h, NULL, 0) < 0) break;
            Os.close(h);
        }
    }

    static int inScope() {
        return memScoped(() -> {
            var h = Os.open("a");
            return Os.read(h, alloc(8), 8);
        });
    }

    static void discarded() { Os.open("a"); }

    static void passedOn() { Os.read(Os.open("a"), NULL, 0); }

    static void overwritten() {
        var h = Os.open("a");
        h = Os.open("b");
        Os.close(h);
    }

    static Address notMarked() { return Os.open("a"); }

    static void undeclared() { throw new Fail("x"); }

    static void owned(@Owns("close") Address h) { Os.read(h, NULL, 0); }

    static int switchArm(int k) {
        var h = Os.open("a");
        return switch (k) {
            case 0 -> 1;
            default -> {
                Os.close(h);
                yield 2;
            }
        };
    }
}
