package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

/**
 * jr's own command-line options, in the style of java's -X options, mirroring launcher.c's
 * parseJrOptions/oldFlagReplacement:
 *
 *   -Xjr:&lt;key&gt;=&lt;value&gt;            any config key, overriding the baked-in config (-Xjr:jvm=dll)
 *   -Xjr:yes                      don't ask before auto-installing Java
 *   -Xjr:help                     show help
 *
 * Only a LEADING run of -Xjr: tokens belongs to jr. Parsing stops at the first token that is not
 * one, and everything from there on goes to the app exactly as typed - unlike launcher.c, which had
 * to re-tokenise a single command-line string, TeaVM's args[] already arrives pre-split, so "the
 * leading run of tokens" is simply a prefix of the array.
 */
public final class JrOptions {
    public boolean assumeYes;
    public boolean help;
    public String error; // non-null = a bad option, message for the user
    public List<String> appArgs = new ArrayList<>();
    public final ReStamp stamp = new ReStamp(); // -Xjr:make/edit/icon/version/sign... (PRP-20 phase 2)

    private JrOptions() {}

    /** Consumes the leading -Xjr: options, applying -Xjr:key=value into config as it goes. */
    public static JrOptions parse(String[] args, Config config) {
        var opts = new JrOptions();
        var i = 0;
        while (i < args.length) {
            var token = args[i];
            if (!token.startsWith("-Xjr:")) {
                break;
            }
            i++;
            var opt = token.substring(5);

            var reResult = opts.stamp.parseOption(opt);
            if (reResult < 0) {
                opts.error = opts.stamp.error;
                break;
            }
            if (reResult > 0) {
                continue;
            }

            if (opt.equals("yes")) {
                opts.assumeYes = true;
            } else if (opt.equals("help")) {
                opts.help = true;
            } else if (opt.equals("create-config") || opt.startsWith("create-config=")) {
                opts.error = NO_CREATE_CONFIG;
                break;
            } else {
                var eq = opt.indexOf('=');
                if (eq >= 0) {
                    var key = opt.substring(0, eq);
                    var value = opt.substring(eq + 1);
                    if (!config.applyKey(key, value)) {
                        opts.error = "Unknown jr option: -Xjr:" + key + "=...\n\nThe config keys include "
                                + "vm.args, java.args, app.args, aot, jvm, java.home, java.version, "
                                + "java.autoinstall, log.file, log.level and log.overwrite.";
                        break;
                    }
                } else {
                    opts.error = "Unknown jr option: -Xjr:" + opt + "\n\nOptions: -Xjr:<key>=<value>, -Xjr:yes, "
                            + "-Xjr:help";
                    break;
                }
            }
        }
        for (; i < args.length; i++) {
            opts.appArgs.add(args[i]);
        }
        return opts;
    }

    /**
     * jr's flags used to be --jvm-dll, --java-home=... and so on. They are gone, because they were
     * matched anywhere on the command line and collided with the app's own arguments. Without a
     * java.args the first argument must be the jar, so an old flag there is unambiguous and gets
     * pointed at its replacement rather than being handed to java as a jar name.
     */
    public static String oldFlagReplacement(List<String> appArgs) {
        if (appArgs.isEmpty()) {
            return null;
        }
        var first = appArgs.get(0);
        for (var i = 0; i < OLD_FLAGS.length; i++) {
            var flag = OLD_FLAGS[i][0];
            if (first.equals(flag) || first.startsWith(flag + "=")) {
                return OLD_FLAGS[i][1];
            }
        }
        return null;
    }

    /** -Xjr:create-config wrote an old key=value .jrc beside the exe, which jr no longer reads (PRP-38). */
    static final String NO_CREATE_CONFIG = "-Xjr:create-config is gone: jr no longer reads a .jrc file beside the binary.\n\n"
            + "To give an app its own binary with its config baked in, build it with jr-maven-plugin:\n" + Config.GUIDE;

    private static final String[][] OLD_FLAGS = {
        {"--jvm-dll", "-Xjr:jvm=dll"}, {"--jvm-exe", "-Xjr:jvm=exe"},
        {"--enable-aot", "-Xjr:aot=true"}, {"--disable-aot", "-Xjr:aot=false"},
        {"--java-home", "-Xjr:java.home=PATH"}, {"--yes", "-Xjr:yes"},
        {"--create-config", "jr-maven-plugin (" + Config.GUIDE + ")"},
    };
}
