package memlab.probe;

import memlab.C;
import memlab.ffi.Ffi;

public final class SizeFfi {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = Ffi.i(m -> C.strcmp(m.c(a), m.c("alpha")) + (int) C.strlen(m.c(a + "!")));
        System.out.println(r);
    }
}
