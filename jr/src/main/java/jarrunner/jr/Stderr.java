package jarrunner.jr;

import static jarrunner.jr.N.*;

/** The real stderr. TeaVM's C runtime sends System.err through the same putwchar as System.out,
 *  so a System.err message lands on stdout - where it corrupts the output of an app whose stdout
 *  is data (measured, PRP-30: a download progress bar inside a CLI's piped output). Everything jr
 *  says about itself that is not the answer to a question (progress, prompts, errors) goes here. */
public final class Stderr {
    private Stderr() {}

    public static void print(String s) {
        if (s == null || s.isEmpty()) return;
        memScoped(() -> {
            var buf = alloc(s.length() * 3);
            var n = 0;
            for (var i = 0; i < s.length(); i++) {
                int c = s.charAt(i);
                if (Character.isHighSurrogate((char) c) && i + 1 < s.length()) {
                    c = Character.toCodePoint((char) c, s.charAt(++i));
                }
                if (c < 0x80) {
                    buf.add(n++).putByte((byte) c);
                } else if (c < 0x800) {
                    buf.add(n++).putByte((byte) (0xC0 | c >> 6));
                    buf.add(n++).putByte((byte) (0x80 | c & 0x3F));
                } else if (c < 0x10000) {
                    buf.add(n++).putByte((byte) (0xE0 | c >> 12));
                    buf.add(n++).putByte((byte) (0x80 | c >> 6 & 0x3F));
                    buf.add(n++).putByte((byte) (0x80 | c & 0x3F));
                } else {
                    buf.add(n++).putByte((byte) (0xF0 | c >> 18));
                    buf.add(n++).putByte((byte) (0x80 | c >> 12 & 0x3F));
                    buf.add(n++).putByte((byte) (0x80 | c >> 6 & 0x3F));
                    buf.add(n++).putByte((byte) (0x80 | c & 0x3F));
                }
            }
            WinApi.writeFile(WinApi.getStdHandle(WinApi.STD_ERROR_HANDLE), buf, n, intVar(), NULL);
        });
    }

    public static void println(String s) {
        print(s + "\n");
    }

    public static void println() {
        print("\n");
    }
}
