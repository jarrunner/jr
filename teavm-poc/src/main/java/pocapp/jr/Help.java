package pocapp.jr;

/** The diagnostic/help text shown when jr is run with no jar argument, mirroring launcher.c's
 *  showHelp - now including phase 2's branded-launcher/resource-editing/signing options, see
 *  20-prp-teavm_port_catches_up_with_the_c_launcher.md. */
public final class Help {
    private Help() {}

    public static void show(boolean hasConsole, String exeBaseName, String javaExeName, String javaPath,
            String configPath, boolean configFound, int useJvmDll) {
        var info = "Java Runner (jr) - Smart Java Launcher\n\n"
                + "Execution Context: " + (hasConsole ? "Console (terminal/cmd)" : "GUI (double-clicked)") + "\n"
                + "Launch Mode: " + (useJvmDll == 1 ? "in-process (jvm.dll)" : "child process (java.exe)") + "\n"
                + "Java Executable: " + javaExeName + "\n"
                + "Java Location: " + (javaPath != null ? javaPath : "(none in PATH)") + "\n"
                + "Config File: " + configPath + " (" + (configFound ? "found" : "not found") + ")\n\n"
                + "Usage:\n"
                + "  " + exeBaseName + ".exe [-Xjr:options] <jar-file> [args...]\n"
                + "  " + exeBaseName + ".exe [-Xjr:options] [args...]     (with java.args in the .jrc)\n\n"
                + "jr options must come first; everything after them goes to the app untouched.\n"
                + "  -Xjr:<key>=<value>          any .jrc key, overriding the .jrc\n"
                + "                              e.g. -Xjr:jvm=dll  -Xjr:aot=false  -Xjr:java.home=PATH\n"
                + "  -Xjr:yes                    don't ask before auto-installing Java\n"
                + "  -Xjr:create-config[=<jar>]  write a sample " + exeBaseName + ".jrc\n"
                + "  -Xjr:help                   this help\n\n"
                + "Making a branded launcher (no Java involved):\n"
                + "  -Xjr:make=<out.exe> | -Xjr:edit=<exe>    copy this exe, or edit one in place, then:\n"
                + "  -Xjr:icon=<file.ico>        -Xjr:version=<a.b.c.d>   -Xjr:version.<Name>=<text>\n"
                + "  -Xjr:manifest=<file>        -Xjr:execution-level=asInvoker|highestAvailable|requireAdministrator\n"
                + "  -Xjr:string.<id>=<text>     -Xjr:resource.<type>.<name>=<file>\n"
                + "  -Xjr:sign=<file.pfx> (password in JR_SIGN_PASSWORD) | -Xjr:sign.thumbprint=<sha1>\n"
                + "  -Xjr:sign.timestamp=<url>   -Xjr:list-resources=<exe>\n\n"
                + "If Java is not found, or the .jrc's java.version (NN = exactly NN,\n"
                + "NN+ = NN or newer) doesn't match the one in PATH, jr offers to download\n"
                + "a matching Eclipse Temurin JDK into %USERPROFILE%\\.jbang\\cache\\jdks\\<version>\n"
                + "(same cache jbang itself uses). Disable via .jrc: java.autoinstall=false\n\n"
                + "Examples:\n"
                + "  " + exeBaseName + ".exe myapp.jar\n"
                + "  " + exeBaseName + ".exe -Xjr:jvm=dll myapp.jar\n"
                + "  " + exeBaseName + ".exe -Xjr:create-config=myapp.jar\n"
                + "  " + exeBaseName + ".exe -Xjr:java.home=C:\\Java\\jdk21 myapp.jar --verbose";
        Ui.info(hasConsole, "Java Runner - Help", info);
    }
}
