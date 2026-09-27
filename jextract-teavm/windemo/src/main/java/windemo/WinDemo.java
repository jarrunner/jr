package windemo;

import org.teavm.interop.Address;
import org.teavm.interop.Function;
import windemo.bind.Win;

import static windemo.bind.Win.*;

/**
 * Calls back from C into Java through the GENERATED callback types, and uses the generated function-like macros.
 * Exit code = number of failed checks.
 */
public class WinDemo {
    static final long COOKIE = 0x1122334455667788L;
    static int windows, badCookie, failures;
    static byte[] survivor, junk;
    static long allocated;

    public static void main(String[] args) {
        survivor = new byte[1 << 20];
        survivor[12345] = 42;
        var enumProc = (Address) (Object) Function.get(WNDENUMPROC.class, WinDemo.class, "onWindow");
        var ok = enumWindows(enumProc, COOKIE);
        check(ok != 0 && windows > 0, "EnumWindows -> WNDENUMPROC callback, " + windows + " calls");
        check(badCookie == 0, "64-bit LPARAM arrives intact in every callback");
        check(allocated > 64L << 20 && survivor[12345] == 42, "GC inside the callback: " + (allocated >> 20) + " MB allocated, held array intact");

        var ints = new int[] {5, -3, 9, 0, 7, -3, 100, 1};
        var buf = new byte[ints.length * 4];
        var at = Address.ofData(buf);
        for (var i = 0; i < ints.length; i++) at.add(i * 4).putInt(ints[i]);
        qsort(Address.ofData(buf), ints.length, 4, (Address) (Object) Function.get(QsortCompar.class, WinDemo.class, "compareInts"));
        var sorted = new StringBuilder();
        var inOrder = true;
        for (var i = 0; i < ints.length; i++) {
            var v = Address.ofData(buf).add(i * 4).getInt();
            if (i > 0 && Address.ofData(buf).add(i * 4 - 4).getInt() > v) inOrder = false;
            sorted.append(i == 0 ? "" : " ").append(v);
        }
        check(inOrder, "qsort -> comparator callback (qsort(4)): " + sorted);

        check(loword(0x22221111) == 0x1111, "LOWORD(0x22221111) = 0x" + Integer.toHexString(loword(0x22221111) & 0xffff));
        check(hiword(0x22221111) == 0x2222, "HIWORD(0x22221111) = 0x" + Integer.toHexString(hiword(0x22221111) & 0xffff));
        check(makeLong(0x1111, 0x2222) == 0x22221111, "MAKELONG(0x1111, 0x2222) = 0x" + Integer.toHexString(makeLong(0x1111, 0x2222)));
        check(makeLParam(0xffff, 0x7fff) == 0x7fffffffL, "MAKELPARAM(0xffff, 0x7fff) = 0x" + Long.toHexString(makeLParam(0xffff, 0x7fff)));

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        Win.exit(failures);
    }

    static int onWindow(Address hwnd, long lParam) {
        windows++;
        if (lParam != COOKIE) badCookie++;
        for (var i = 0; i < 64; i++) allocated += (junk = new byte[64 * 1024]).length;
        return 1;
    }

    static int compareInts(Address a, Address b) {
        return Integer.compare(a.getInt(), b.getInt());
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
