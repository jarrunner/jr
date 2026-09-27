package windemo;

import org.teavm.interop.Address;
import windemo.shim.ShimStructs.DivT;
import windemo.shim.ShimStructs.POINT;

import static windemo.shim.Shim.*;

/** A struct by value, in and out, through a one-line header macro bound with macro: (byvalue-shim.h). */
public class ByValueShim {
    public static void main(String[] args) {
        var pt = new byte[POINT.SIZE];
        POINT.x(Address.ofData(pt), 10);
        POINT.y(Address.ofData(pt), 10);
        var hwnd = windowFromPoint(Address.ofData(pt));
        var res = new byte[DivT.SIZE];
        div(17, 5, Address.ofData(res));
        var quot = DivT.quot(Address.ofData(res));
        var rem = DivT.rem(Address.ofData(res));
        System.out.println("WindowFromPoint(10,10) = 0x" + Long.toHexString(hwnd.toLong()) + (hwnd.toLong() != 0 ? "  ok" : "  FAIL"));
        System.out.println("div(17, 5) = " + quot + " rem " + rem + (quot == 3 && rem == 2 ? "  ok" : "  FAIL"));
        exitCode(hwnd.toLong() != 0 && quot == 3 && rem == 2 ? 0 : 1);
    }

    @org.teavm.interop.Import(name = "exit") static native void exitCode(int code);
}
