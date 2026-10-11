package jarrunner.jr;

/** The diagnostic/help text for PosixJr - trimmed to this build's actual feature set (jvm=dll since PRP-42, the macOS
 *  default; Java download since PRP-42; no resource editing/signing - see PosixJr's class comment for why). */
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
                + "  -Xjr:batch                  with either: no prompts, one JSON line on stdout (for apps)\n"
                + "  -Xjr:install                macOS: put the app in ~/Applications and a link in ~/.local/bin\n"
                + "  -Xjr:jvm=dll|exe            Java inside this process (libjli; the macOS default) or as a child\n"
                + "  -Xjr:yes                    download Java without asking, when the app's Java is missing\n"
                + "  -Xjr:help                   this help\n\n"
                + "Without the app's preferred Java, jr offers to download it (Eclipse Temurin) into\n"
                + "~/.jbang/cache/jdks, unless the config sets java.autoinstall to false.\n\n"
                + "Not in this build:\n"
                + "  -Xjr:make/edit/icon/sign/... - Windows exe editing; jr-maven-plugin builds macOS apps.\n\n"
                + "Examples:\n"
                + "  " + exeBaseName + " myapp.jar\n"
                + "  " + exeBaseName + " -Xjr:java.home=/usr/lib/jvm/jdk-21 myapp.jar --verbose";
        Stderr.out(info + "\n");
    }
}
