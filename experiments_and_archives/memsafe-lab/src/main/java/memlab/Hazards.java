package memlab;

import memlab.ffi.Pin;
import org.teavm.interop.Address;

import static memlab.Lab.report;
import static memlab.Old.*;

public final class Hazards {
    private Hazards() {}

    static byte[] held;
    static Object gap;

    static void free() {
        var a = cstr("only-an-address-keeps-me");
        Gc.stormAndRefill();
        report("free", "only-an-address-keeps-me", read(a));
    }

    static void move() {
        gap = new byte[200_000];
        held = bytes("held-by-a-static-field");
        var before = Address.ofData(held);
        gap = null;
        Gc.stormAndRefill();
        var after = Address.ofData(held);
        System.out.println("[move] address before=" + before.toLong() + " after=" + after.toLong()
                + (before.toLong() == after.toLong() ? " (not moved)" : " (MOVED by defragment)"));
        report("move", "held-by-a-static-field", read(before));
    }

    static void two() {
        var r = C.strcmp(cstr("apple"), cstrAfterGc("apple"));
        report("two", "strcmp=0", "strcmp=" + r);
        var left = cstr("left-string-one");
        var right = cstrAfterGc("right-string-two");
        report("two.left", "left-string-one", read(left));
        report("two.right", "right-string-two", read(right));
    }

    static void live() {
        var buf = bytes("a-live-local-is-pinned");
        var a = Address.ofData(buf);
        Gc.stormAndRefill();
        var same = a.toLong() == Address.ofData(buf).toLong();
        report("live", "a-live-local-is-pinned same=true", read(a) + " same=" + same);
    }

    static void pin() {
        var r1 = Pin.with(bytes("pinned-by-Address.pin"), a -> { Gc.stormAndRefill(); return check(a, "pinned-by-Address.pin"); });
        report("pin.Address.pin", "1", "" + r1);
        var r2 = Pin.withLength(bytes("pinned-by-a-later-use"), a -> { Gc.stormAndRefill(); return check(a, "pinned-by-a-later-use"); });
        report("pin.later-use", "1", "" + r2);
        var r3 = Pin.withNoPin(bytes("nothing-keeps-me-alive"), a -> { Gc.stormAndRefill(); return check(a, "nothing-keeps-me-alive"); });
        report("pin.none", "1", "" + r3);
    }

    static long check(Address a, String expect) {
        var got = read(a);
        if (!expect.equals(got)) System.out.println("      (read back: " + got + ")");
        return expect.equals(got) ? 1 : 0;
    }
}
