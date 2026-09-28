package pocapp;

import org.teavm.interop.Import;

public class Main {
    /* Runtime.getRuntime().exit(int) / System.exit(int) are both no-ops in TeaVM
       0.15.0's C backend classlib - execution just falls through past them.
       A direct @Import binding to libc's own exit() is what actually works. */
    @Import(name = "exit")
    private static native void cExit(int code);

    public static void main(String[] args) {
        System.out.println("hello from teavm+tcc poc, argc=" + args.length);
        cExit(args.length);
    }
}
