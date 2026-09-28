package littlejlib.jr;

/** The diagnostic/help text for PosixJr - trimmed to this build's actual feature set (no jvm-dll,
 *  no resource editing/signing, no auto-install - see PosixJr's class comment for why). */
public final class Help {
    private Help() {}

    public static void show(String exeBaseName, String javaExeName, String javaPath, String configPath,
            boolean configFound) {
        var info = "Java Runner (jr) - Smart Java Launcher [Linux/macOS]\n\n"
                + "Java Executable: " + javaExeName + "\n"
                + "Java Location: " + (javaPath != null ? javaPath : "(none in PATH)") + "\n"
                + "Config File: " + configPath + " (" + (configFound ? "found" : "not found") + ")\n\n"
                + "Usage:\n"
                + "  " + exeBaseName + " [-Xjr:options] <jar-file> [args...]\n"
                + "  " + exeBaseName + " [-Xjr:options] [args...]     (with java.args in the .jrc)\n\n"
                + "jr options must come first; everything after them goes to the app untouched.\n"
                + "  -Xjr:<key>=<value>          any .jrc key, overriding the .jrc\n"
                + "                              e.g. -Xjr:aot=false  -Xjr:java.home=PATH\n"
                + "  -Xjr:create-config[=<jar>]  write a sample " + exeBaseName + ".jrc\n"
                + "  -Xjr:help                   this help\n\n"
                + "Not in this build (see prp/21-prp-three-os-port-windows-mac-primary-linux-proxy.md):\n"
                + "  JDK auto-install - point java.home at one, or put a matching java on PATH.\n"
                + "  jvm=dll in-process launch - Windows-only, no POSIX equivalent.\n"
                + "  -Xjr:make/edit/icon/sign/... - PE resource editing, meaningless on this platform.\n\n"
                + "If the .jrc's java.version (NN = exactly NN, NN+ = NN or newer) doesn't match\n"
                + "the java found, jr reports the mismatch and stops rather than picking one for you.\n\n"
                + "Examples:\n"
                + "  " + exeBaseName + " myapp.jar\n"
                + "  " + exeBaseName + " -Xjr:create-config=myapp.jar\n"
                + "  " + exeBaseName + " -Xjr:java.home=/usr/lib/jvm/jdk-21 myapp.jar --verbose";
        System.out.println(info);
    }
}
