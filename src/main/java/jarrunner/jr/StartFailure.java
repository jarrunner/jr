package jarrunner.jr;

/** PRP-31: reads what a failed launch printed. Tells "the JVM never started" (safe to retry: the app's code
 *  never ran) from the app's own failure, and names an option the JVM refused and where it came from. */
public final class StartFailure {
    private StartFailure() {}

    private static final String[] NOT_STARTED = {"Could not create the Java Virtual Machine",
            "Error occurred during initialization of VM", "Unrecognized VM option", "Unrecognized option:",
            "Invalid -Xlog option", "Improperly specified VM option"};
    private static final String[] OPTION_STARTS = {"Unrecognized VM option '", "Invalid -Xlog option '",
            "Improperly specified VM option '", "Unrecognized option: "};

    static boolean jvmDidNotStart(String text) {
        for (var m : NOT_STARTED) {
            if (text.contains(m)) {
                return true;
            }
        }
        return false;
    }

    static boolean nativeCrash(String text) {
        return text.contains("A fatal error has been detected by the Java Runtime Environment");
    }

    /** The option the JVM refused, as it printed it, or null. */
    static String rejectedOption(String text) {
        for (var start : OPTION_STARTS) {
            var at = text.indexOf(start);
            if (at >= 0) {
                var from = at + start.length();
                var end = start.endsWith("'") ? text.indexOf('\'', from) : text.indexOf('\n', from);
                return (end < 0 ? text.substring(from) : text.substring(from, end)).strip();
            }
        }
        return null;
    }

    /** Where an option came from, so the message can say who has to change it. */
    static String sourceOf(String option, Config config, String commandLineArgs) {
        if (config.vmArgs.contains(option)) {
            return "the app config (vm.args)";
        }
        if (commandLineArgs.contains(option)) {
            return "the command line";
        }
        for (var env : new String[] {"JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"}) {
            var v = Cstr.readEnv(env);
            if (v != null && v.contains(option)) {
                return "the " + env + " environment variable";
            }
        }
        return option.startsWith("-XX:AOT") || option.startsWith("-XX:ErrorFile") || option.startsWith("-Dio.github.jarrunner.jr")
                ? "jr itself (its AOT cache or launch settings)" : "an unknown source";
    }
}
