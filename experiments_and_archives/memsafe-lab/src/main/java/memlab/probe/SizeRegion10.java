package memlab.probe;

import memlab.C;
import memlab.ffi.F;

public final class SizeRegion10 {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = 0;
        r += C.strcmp(F.c(a + "1"), F.c("alpha1")) + (int) C.strlen(F.c(a + "!1"));
        r += C.strcmp(F.c(a + "2"), F.c("alpha2")) + (int) C.strlen(F.c(a + "!2"));
        r += C.strcmp(F.c(a + "3"), F.c("alpha3")) + (int) C.strlen(F.c(a + "!3"));
        r += C.strcmp(F.c(a + "4"), F.c("alpha4")) + (int) C.strlen(F.c(a + "!4"));
        r += C.strcmp(F.c(a + "5"), F.c("alpha5")) + (int) C.strlen(F.c(a + "!5"));
        r += C.strcmp(F.c(a + "6"), F.c("alpha6")) + (int) C.strlen(F.c(a + "!6"));
        r += C.strcmp(F.c(a + "7"), F.c("alpha7")) + (int) C.strlen(F.c(a + "!7"));
        r += C.strcmp(F.c(a + "8"), F.c("alpha8")) + (int) C.strlen(F.c(a + "!8"));
        r += C.strcmp(F.c(a + "9"), F.c("alpha9")) + (int) C.strlen(F.c(a + "!9"));
        r += C.strcmp(F.c(a + "10"), F.c("alpha10")) + (int) C.strlen(F.c(a + "!10"));
        System.out.println(r);
    }
}
