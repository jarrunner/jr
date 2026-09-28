package memlab;

import memlab.ffi.Ffi;
import org.teavm.interop.Address;

import static memlab.Lab.report;

public final class ApiDemos {
    private ApiDemos() {}

    static void two() {
        var r = Ffi.i(m -> C.strcmp(m.c("apple"), gcThen(m.c("apple"))));
        report("ffi.two", "strcmp=0", "strcmp=" + r);
        var both = Ffi.s(m -> {
            var left = m.c("left-string-one");
            Gc.stormAndRefill();
            var right = m.c("right-string-two" + " built with concat " + 42);
            Gc.stormAndRefill();
            return m.str(left) + "|" + m.str(right);
        });
        report("ffi.two.both", "left-string-one|right-string-two built with concat 42", both);
    }

    static Address gcThen(Address a) {
        Gc.stormAndRefill();
        return a;
    }

    static void escape() {
        var leaked = new long[1];
        Ffi.run(m -> leaked[0] = m.c("do-not-take-me-home").toLong());
        var b = Address.fromLong(leaked[0]).getByte() & 0xFF;
        report("ffi.escape", "0xdd", "0x" + Integer.toHexString(b));
    }

    static void nest() {
        var r = Ffi.s(m -> {
            var outer = m.c("outer");
            var inner = Ffi.s(m2 -> m2.str(m2.c("inner")) + ":" + m2.str(m2.buf(2_000_000)).length());
            var big = m.buf(3_000_000);
            big.add(2_999_999).putByte((byte) 7);
            return m.str(outer) + "/" + inner + "/" + big.add(2_999_999).getByte();
        });
        report("ffi.nest", "outer/inner:0/7", r);
        System.out.println("      " + Ffi.stats());
    }

    static void argv() {
        var r = Ffi.s(m -> {
            var argv = m.ptrs("java", "-jar", "app.jar", "two words");
            Gc.stormAndRefill();
            var sb = new StringBuilder();
            for (var i = 0; i < 4; i++) sb.append(m.str(argv.add(i * Address.sizeOf()).getAddress())).append(',');
            return sb + (argv.add(4 * Address.sizeOf()).getAddress().toLong() == 0 ? "NULL" : "?");
        });
        report("ffi.argv", "java,-jar,app.jar,two words,NULL", r);
    }

    static void wide() {
        var value = "ünïcødé ✓ देव";
        var got = Ffi.s(m -> {
            C.setEnvironmentVariableW(m.w("MEMLAB_W"), m.w(value));
            var buf = m.buf(512);
            Gc.stormAndRefill();
            var n = C.getEnvironmentVariableW(m.w("MEMLAB_W"), buf, 256);
            return n == 0 ? null : m.wstr(buf);
        });
        report("ffi.wide", codes(value), codes(got));
    }

    static String codes(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder();
        for (var i = 0; i < s.length(); i++) sb.append(Integer.toHexString(s.charAt(i))).append(' ');
        return sb.toString().trim();
    }

    static void thrown() {
        try {
            Ffi.run(m -> {
                m.c("about-to-throw");
                m.buf(1_000_000);
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException e) {
            System.out.println("      caught " + e.getMessage());
        }
        report("ffi.thrown", "top=0 bigLive=0", Ffi.live());
    }
}
