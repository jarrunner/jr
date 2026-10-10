package jarrunner.jr;

/** The diagnostic/help text for PosixJr - trimmed to this build's actual feature set (no jvm-dll,
 *  no resource editing/signing, no auto-install - see PosixJr's class comment for why). */
public final class Help {
    private Help() {}

    public static void show(String exeBaseName, String javaExeName, String javaPath, boolean configEmbedded) {
        var info = "Java Runner (jr) - Smart Java Launcher [Linux/macOS]\n\n"
                + "Java Executable: " + javaExeName + "\n"
                + "Java Location: " + (javaPath != null ? javaPath : "(none in PATH)") + "\n"
                + "Config: " + (configEmbedded ? "embedded in this binary (__DATA,__jrc)" : "none baked in") + "\n\n"
                + "Usage:\n"
                + "  " + exeBaseName + " [-Xjr:options] <jar-file> [args...]\n"
                + "  <app> [-Xjr:options] [args...]      an app's own binary, its config baked in\n\n"
                + "To give your app its own binary or .app (name, icon, config baked in), add\n"
                + "jr-maven-plugin to its Maven build; it works the same in CI. Guide:\n"
                + "  https://github.com/jarrunner/jr/blob/main/docs/guide.md\n\n"
                + "jr options must come first; everything after them goes to the app untouched.\n"
                + "  -Xjr:<key>=<value>          set a config key for this run\n"
                + "                              e.g. -Xjr:aot=false  -Xjr:java.home=PATH\n"
                + "  -Xjr:update-check           is a newer release out? (the app's update.url)\n"
                + "  -Xjr:update                 replace this binary (or its .app) with the newest release\n"
                + "  -Xjr:install                macOS: put the app in ~/Applications and a link in ~/.local/bin\n"
                + "  -Xjr:help                   this help\n\n"
                + "Not in this build:\n"
                + "  JDK auto-install - point java.home at one, or put a matching java on PATH.\n"
                + "  jvm=dll in-process launch - Windows-only, no POSIX equivalent.\n"
                + "  -Xjr:make/edit/icon/sign/... - Windows exe editing; jr-maven-plugin builds macOS apps.\n\n"
                + "If the config's java.version (NN = exactly NN, NN+ = NN or newer) doesn't match\n"
                + "the java found, jr reports the mismatch and stops rather than picking one for you.\n\n"
                + "Examples:\n"
                + "  " + exeBaseName + " myapp.jar\n"
                + "  " + exeBaseName + " -Xjr:java.home=/usr/lib/jvm/jdk-21 myapp.jar --verbose";
        Stderr.out(info + "\n");
    }
}
