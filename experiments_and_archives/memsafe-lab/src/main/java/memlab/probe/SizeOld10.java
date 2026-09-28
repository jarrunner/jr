package memlab.probe;

import memlab.C;
import memlab.Old;

public final class SizeOld10 {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = 0;
        r += C.strcmp(Old.cstr(a + "1"), Old.cstr("alpha1")) + (int) C.strlen(Old.cstr(a + "!1"));
        r += C.strcmp(Old.cstr(a + "2"), Old.cstr("alpha2")) + (int) C.strlen(Old.cstr(a + "!2"));
        r += C.strcmp(Old.cstr(a + "3"), Old.cstr("alpha3")) + (int) C.strlen(Old.cstr(a + "!3"));
        r += C.strcmp(Old.cstr(a + "4"), Old.cstr("alpha4")) + (int) C.strlen(Old.cstr(a + "!4"));
        r += C.strcmp(Old.cstr(a + "5"), Old.cstr("alpha5")) + (int) C.strlen(Old.cstr(a + "!5"));
        r += C.strcmp(Old.cstr(a + "6"), Old.cstr("alpha6")) + (int) C.strlen(Old.cstr(a + "!6"));
        r += C.strcmp(Old.cstr(a + "7"), Old.cstr("alpha7")) + (int) C.strlen(Old.cstr(a + "!7"));
        r += C.strcmp(Old.cstr(a + "8"), Old.cstr("alpha8")) + (int) C.strlen(Old.cstr(a + "!8"));
        r += C.strcmp(Old.cstr(a + "9"), Old.cstr("alpha9")) + (int) C.strlen(Old.cstr(a + "!9"));
        r += C.strcmp(Old.cstr(a + "10"), Old.cstr("alpha10")) + (int) C.strlen(Old.cstr(a + "!10"));
        System.out.println(r);
    }
}
