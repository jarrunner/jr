package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** The real stderr and stdout. TeaVM's C runtime sends System.err through the same putwchar as System.out,
 *  so a System.err message lands on stdout - where it corrupts the output of an app whose stdout is data
 *  (measured, PRP-30: a download progress bar inside a CLI's piped output). Everything jr says about itself
 *  that is not the answer to a question (progress, prompts, errors) goes to stderr; answers go through
 *  {@link #out}. Neither uses System.out: its putwchar runs in the C locale and mangles anything non-ASCII
 *  (PRP-34). A console gets UTF-16 through WriteConsoleW, so every name shows whatever the console's code
 *  page; a pipe or a file gets UTF-8 bytes. */
public final class Stderr {
    private Stderr() {}

    public static void print(String s) {
        write(WinApi.STD_ERROR_HANDLE, s);
    }

    public static void println(String s) {
        print(s + "\n");
    }

    public static void println() {
        print("\n");
    }

    /** stdout, for the answers jr prints (reports, help, -Xjr:doctor). */
    public static void out(String s) {
        write(WinApi.STD_OUTPUT_HANDLE, s);
    }

    private static void write(int std, String s) {
        if (s == null || s.isEmpty()) return;
        memScoped(() -> {
            var h = WinApi.getStdHandle(std);
            if (WinApi.getConsoleMode(h, intVar()) != 0) {
                WinApi.writeConsoleW(h, wcstr(s), s.length(), intVar(), NULL);
                return;
            }
            Address bytes = utf8(s);
            WinApi.writeFile(h, bytes, (int) WinApi.strlen(bytes), intVar(), NULL);
        });
    }
}
