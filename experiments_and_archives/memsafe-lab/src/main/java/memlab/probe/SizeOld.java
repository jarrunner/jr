package memlab.probe;

import memlab.C;
import memlab.Old;

public final class SizeOld {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = C.strcmp(Old.cstr(a), Old.cstr("alpha")) + (int) C.strlen(Old.cstr(a + "!"));
        System.out.println(r);
    }
}
