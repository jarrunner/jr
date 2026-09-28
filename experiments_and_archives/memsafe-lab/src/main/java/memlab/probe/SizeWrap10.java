package memlab.probe;

import memlab.gen.Cx;

public final class SizeWrap10 {
    public static void main(String[] args) {
        var a = args.length > 0 ? args[0] : "alpha";
        var r = 0;
        r += Cx.strcmp(a + "1", "alpha1") + (int) Cx.strlen(a + "!1");
        r += Cx.strcmp(a + "2", "alpha2") + (int) Cx.strlen(a + "!2");
        r += Cx.strcmp(a + "3", "alpha3") + (int) Cx.strlen(a + "!3");
        r += Cx.strcmp(a + "4", "alpha4") + (int) Cx.strlen(a + "!4");
        r += Cx.strcmp(a + "5", "alpha5") + (int) Cx.strlen(a + "!5");
        r += Cx.strcmp(a + "6", "alpha6") + (int) Cx.strlen(a + "!6");
        r += Cx.strcmp(a + "7", "alpha7") + (int) Cx.strlen(a + "!7");
        r += Cx.strcmp(a + "8", "alpha8") + (int) Cx.strlen(a + "!8");
        r += Cx.strcmp(a + "9", "alpha9") + (int) Cx.strlen(a + "!9");
        r += Cx.strcmp(a + "10", "alpha10") + (int) Cx.strlen(a + "!10");
        System.out.println(r);
    }
}
