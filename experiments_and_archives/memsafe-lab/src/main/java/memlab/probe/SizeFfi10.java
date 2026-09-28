package memlab.probe;

import memlab.C;
import memlab.ffi.Ffi;

public final class SizeFfi10 {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = 0;
        r += Ffi.i(m -> C.strcmp(m.c(a + "1"), m.c("alpha1")) + (int) C.strlen(m.c(a + "!1")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "2"), m.c("alpha2")) + (int) C.strlen(m.c(a + "!2")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "3"), m.c("alpha3")) + (int) C.strlen(m.c(a + "!3")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "4"), m.c("alpha4")) + (int) C.strlen(m.c(a + "!4")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "5"), m.c("alpha5")) + (int) C.strlen(m.c(a + "!5")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "6"), m.c("alpha6")) + (int) C.strlen(m.c(a + "!6")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "7"), m.c("alpha7")) + (int) C.strlen(m.c(a + "!7")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "8"), m.c("alpha8")) + (int) C.strlen(m.c(a + "!8")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "9"), m.c("alpha9")) + (int) C.strlen(m.c(a + "!9")));
        r += Ffi.i(m -> C.strcmp(m.c(a + "10"), m.c("alpha10")) + (int) C.strlen(m.c(a + "!10")));
        System.out.println(r);
    }
}
