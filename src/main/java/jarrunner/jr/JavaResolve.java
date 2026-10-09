package jarrunner.jr;

/** PRP-31: the Java a launch uses. An explicit java.home wins (and is still inspected, so the log and the
 *  AOT decision know what it is); otherwise JavaChooser applies the version rules. Shows the error and
 *  returns null when nothing qualifies. */
public final class JavaResolve {
    private JavaResolve() {}

    static JavaHome resolve(Config config, String exeName, boolean hasConsole, boolean guiMode, boolean assumeYes) {
        var range = JavaRange.of(config);
        if (range.error != null) {
            Ui.error(hasConsole, "Invalid Java Version Setting", range.error
                    + "\n\nThis setting is part of the app's own configuration; its author needs to correct it.");
            return null;
        }
        if (range.note != null) {
            Log.info(range.note);
        }
        Log.info("Java wanted: " + range.describe());
        Launch.range = range;

        if (!config.javaHome.isEmpty()) {
            var h = JavaHome.inspect(config.javaHome, "java.home", exeName);
            Log.info("Candidate " + h.describe());
            if (!FileIo.exists(h.javaExe(exeName))) {
                Ui.error(hasConsole, "Java Not Found", "Java not found at specified location:\n" + h.javaExe(exeName)
                        + "\n\nPlease check java.home in the app config, or -Xjr:java.home=.");
                return null;
            }
            if (!h.usable()) {
                Log.warn("java.home: " + h.reject + "; using it because it was named explicitly");
            } else if (!range.accepts(h.major)) {
                Log.warn("java.home is Java " + h.major + ", outside " + range.describe()
                        + "; using it because it was named explicitly");
            }
            return h;
        }

        var chooser = new JavaChooser();
        chooser.hasConsole = hasConsole;
        chooser.guiMode = guiMode;
        chooser.assumeYes = assumeYes;
        chooser.packageType = config.javaType.isEmpty() ? "jre" : config.javaType;
        // Test-only, not in help: pretend no Java is installed except in jr's cache (prp/09).
        chooser.onlyCache = Cstr.readEnv("JR_TEST_FORCE_NO_JAVA") != null;
        var h = chooser.choose(range, exeName, config.javaAutoInstall != 0);
        Launch.chooser = chooser;
        if (h == null) {
            Ui.error(hasConsole, "Java Not Found", "This app needs Java " + range.preferred
                    + (range.max == 0 ? " or newer" : "") + " (" + range.describe() + ").\n\n"
                    + "Java installations found on this computer:\n" + chooser.report()
                    + "\nInstall Java " + range.preferred + ", or set java.home in the app config (or -Xjr:java.home=C:\\path\\to\\jdk).");
            return null;
        }
        Log.info("Using Java " + h.major + ": " + h.home + " (" + chooser.rule + ")");
        return h;
    }
}
