package memlab.probe;

import static memlab.C.*;
import static memlab.nat.N.*;

public final class SizeN10 {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = 0;
        r += memScoped(() -> strcmp(cstr(a + "1"), cstr("alpha1")) + (int) strlen(cstr(a + "!1")));
        r += memScoped(() -> strcmp(cstr(a + "2"), cstr("alpha2")) + (int) strlen(cstr(a + "!2")));
        r += memScoped(() -> strcmp(cstr(a + "3"), cstr("alpha3")) + (int) strlen(cstr(a + "!3")));
        r += memScoped(() -> strcmp(cstr(a + "4"), cstr("alpha4")) + (int) strlen(cstr(a + "!4")));
        r += memScoped(() -> strcmp(cstr(a + "5"), cstr("alpha5")) + (int) strlen(cstr(a + "!5")));
        r += memScoped(() -> strcmp(cstr(a + "6"), cstr("alpha6")) + (int) strlen(cstr(a + "!6")));
        r += memScoped(() -> strcmp(cstr(a + "7"), cstr("alpha7")) + (int) strlen(cstr(a + "!7")));
        r += memScoped(() -> strcmp(cstr(a + "8"), cstr("alpha8")) + (int) strlen(cstr(a + "!8")));
        r += memScoped(() -> strcmp(cstr(a + "9"), cstr("alpha9")) + (int) strlen(cstr(a + "!9")));
        r += memScoped(() -> strcmp(cstr(a + "10"), cstr("alpha10")) + (int) strlen(cstr(a + "!10")));
        System.out.println(r);
    }
}
