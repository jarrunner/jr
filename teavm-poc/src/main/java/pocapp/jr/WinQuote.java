package pocapp.jr;

/**
 * Re-quotes a single app argument for CreateProcessA, using the standard algorithm every Windows
 * argv parser (CommandLineToArgvW, the CRT's own, JLI_CmdToArgs) uses to split a command line back
 * apart. Backslashes are literal except immediately before a quote, where they must be doubled and
 * the quote itself escaped with one more backslash; a run of backslashes right before the closing
 * quote is doubled too, so the parser doesn't read the last one as escaping that closing quote.
 *
 * launcher.c never needed this: it re-emitted the original GetCommandLineA bytes for the app's own
 * arguments verbatim, quotes and all. TeaVM's C backend hands main(String[] args) already split by
 * the OS/CRT, which necessarily discards how each argument was quoted - so rebuilding a command
 * line from that args[] has to requote from scratch. This reconstructs quoting that round-trips
 * (the receiving parser sees exactly the same argument back), not the original bytes - there is
 * nothing to recover: "two words" means the same argument whether it was typed quoted or not.
 */
public final class WinQuote {
    private WinQuote() {}

    public static String quote(String arg) {
        if (!arg.isEmpty() && needsNoQuoting(arg)) {
            return arg;
        }
        var sb = new StringBuilder();
        sb.append('"');
        var backslashes = 0;
        for (var i = 0; i < arg.length(); i++) {
            var c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                appendBackslashes(sb, backslashes * 2 + 1);
                backslashes = 0;
                sb.append('"');
                continue;
            }
            appendBackslashes(sb, backslashes);
            backslashes = 0;
            sb.append(c);
        }
        appendBackslashes(sb, backslashes * 2);
        sb.append('"');
        return sb.toString();
    }

    private static boolean needsNoQuoting(String arg) {
        for (var i = 0; i < arg.length(); i++) {
            switch (arg.charAt(i)) {
                case ' ', '\t', '\n', 0x0B, '"' -> {
                    return false;
                }
                default -> { }
            }
        }
        return true;
    }

    private static void appendBackslashes(StringBuilder sb, int n) {
        for (var i = 0; i < n; i++) {
            sb.append('\\');
        }
    }
}
