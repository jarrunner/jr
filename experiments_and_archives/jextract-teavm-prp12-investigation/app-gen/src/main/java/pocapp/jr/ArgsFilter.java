package pocapp.jr;

import java.util.ArrayList;
import java.util.List;

/** Scans the raw args[] for launcher-only flags and strips them, mirroring launcher.c's
 *  extractJavaHome/removeJavaHomeArg/removeFlagArg family - simpler here since TeaVM already
 *  tokenizes argv for us, unlike the C original which re-parsed a single GetCommandLineA string. */
public final class ArgsFilter {
    private ArgsFilter() {}

    public static String javaHome(String[] args) {
        for (var i = 0; i < args.length; i++) {
            if (args[i].startsWith("--java-home=")) {
                return args[i].substring("--java-home=".length());
            }
            if (args[i].equals("--java-home") && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return null;
    }

    public static boolean has(String[] args, String flag) {
        for (var a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /** The token right after --create-config, if it's not itself a flag. */
    public static String createConfigJarArg(String[] args) {
        for (var i = 0; i < args.length; i++) {
            if (args[i].equals("--create-config") && i + 1 < args.length && !args[i + 1].startsWith("-")) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** Everything left after stripping launcher-only flags (and --java-home's value token). */
    public static List<String> stripLauncherFlags(String[] args) {
        var result = new ArrayList<String>();
        for (var i = 0; i < args.length; i++) {
            var a = args[i];
            if (a.startsWith("--java-home=") || a.equals("--disable-aot") || a.equals("--enable-aot")
                    || a.equals("--jvm-dll") || a.equals("--jvm-exe") || a.equals("--create-config")
                    || a.equals("--yes")) {
                continue;
            }
            if (a.equals("--java-home")) {
                i++; // also skip its value
                continue;
            }
            result.add(a);
        }
        return result;
    }
}
