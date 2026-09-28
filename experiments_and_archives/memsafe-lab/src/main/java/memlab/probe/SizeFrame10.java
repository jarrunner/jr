package memlab.probe;

import memlab.C;
import memlab.ffi.F;

public final class SizeFrame10 {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = 0;
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "1"), F.c("alpha1")) + (int) C.strlen(F.c(a + "!1"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "2"), F.c("alpha2")) + (int) C.strlen(F.c(a + "!2"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "3"), F.c("alpha3")) + (int) C.strlen(F.c(a + "!3"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "4"), F.c("alpha4")) + (int) C.strlen(F.c(a + "!4"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "5"), F.c("alpha5")) + (int) C.strlen(F.c(a + "!5"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "6"), F.c("alpha6")) + (int) C.strlen(F.c(a + "!6"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "7"), F.c("alpha7")) + (int) C.strlen(F.c(a + "!7"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "8"), F.c("alpha8")) + (int) C.strlen(F.c(a + "!8"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "9"), F.c("alpha9")) + (int) C.strlen(F.c(a + "!9"));
        } finally { F.reset(mk); } }
        { var mk = F.mark(); try {
            r += C.strcmp(F.c(a + "10"), F.c("alpha10")) + (int) C.strlen(F.c(a + "!10"));
        } finally { F.reset(mk); } }
        System.out.println(r);
    }
}
