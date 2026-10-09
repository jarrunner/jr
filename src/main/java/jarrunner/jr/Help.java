package jarrunner.jr;

/** The diagnostic/help text shown when jr is run with no jar argument, mirroring launcher.c's
 *  showHelp - now including phase 2's branded-launcher/resource-editing/signing options, see
 *  20-prp-teavm_port_catches_up_with_the_c_launcher.md. */
public final class Help {
    private Help() {}

    public static void show(boolean hasConsole, String exeBaseName, String javaExeName, String javaPath,
            String configLabel, int useJvmDll) {
        var info = "Java Runner (jr) - Smart Java Launcher\n\n"
                + "Execution Context: " + (hasConsole ? "Console (terminal/cmd)" : "GUI (double-clicked)") + "\n"
                + "Launch Mode: " + (useJvmDll == 1 ? "in-process (jvm.dll)" : "child process (java.exe)") + "\n"
                + "Java Executable: " + javaExeName + "\n"
                + "Java Location: " + (javaPath != null ? javaPath : "(none in PATH)") + "\n"
                + "Config: " + configLabel + "\n\n"
                + "Usage:\n"
                + "  " + exeBaseName + ".exe [-Xjr:options] <jar-file> [args...]\n"
                + "  " + exeBaseName + ".exe [-Xjr:options] [args...]     (with java.args in the .jrc)\n\n"
                + "jr options must come first; everything after them goes to the app untouched.\n"
                + "  -Xjr:<key>=<value>          any .jrc key, overriding the .jrc\n"
                + "                              e.g. -Xjr:jvm=dll  -Xjr:aot=false  -Xjr:java.home=PATH\n"
                + "  -Xjr:yes                    don't ask before auto-installing Java\n"
                + "  -Xjr:create-config[=<jar>]  write a sample " + exeBaseName + ".jrc\n"
                + "  -Xjr:doctor                 show which Java a launch would use and why, and the\n"
                + "                              state of the jar, AOT cache and crash logs (changes nothing)\n"
                + "  -Xjr:repair                 fix what jr made: AOT caches, a changed jar, damaged JDKs\n"
                + "                              in its download cache (asks before downloading)\n"
                + "  -Xjr:help                   this help\n\n"
                + "Making a branded launcher (no Java involved):\n"
                + "  -Xjr:make=<out.exe> | -Xjr:edit=<exe>    copy this exe, or edit one in place, then:\n"
                + "  -Xjr:icon=<file.ico>        -Xjr:version=<a.b.c.d>   -Xjr:version.<Name>=<text>\n"
                + "                              (a 256x256, 8-bit-per-channel, PNG-compressed entry\n"
                + "                              is the best size/quality tradeoff, typically 5-15 KB for a\n"
                + "                              simple icon - Windows downscales that cleanly for every\n"
                + "                              smaller size it needs, so extra sizes in the .ico mostly\n"
                + "                              just add exe weight. A plain ImageMagick -define\n"
                + "                              icon:auto-resize=... can silently write smaller sizes as\n"
                + "                              uncompressed BMP and 16-bit-per-channel PNG - check what\n"
                + "                              your .ico actually contains before trusting its size.)\n"
                + "  -Xjr:manifest=<file>        -Xjr:execution-level=asInvoker|highestAvailable|requireAdministrator\n"
                + "  -Xjr:string.<id>=<text>     -Xjr:resource.<type>.<name>=<file>\n"
                + "  -Xjr:resource.RCDATA.JRC=<app.jrc>   embed the config itself; the exe then needs\n"
                + "                              no sibling .jrc (a .jrc file next to it still wins)\n"
                + "  -Xjr:sign=<file.pfx> (password in JR_SIGN_PASSWORD) | -Xjr:sign.thumbprint=<sha1>\n"
                + "  -Xjr:sign.timestamp=<url>   -Xjr:list-resources=<exe>\n\n"
                + "Java version: java.min / java.preferred / java.max (java.version=NN is\n"
                + "min and preferred NN). jr uses an installed Java of exactly the preferred\n"
                + "version; if there is none it offers to download one, an Eclipse Temurin JRE\n"
                + "(java.type=jdk for the full JDK); only then an installed newer (or older,\n"
                + "down to min) Java. AOT (on unless aot=false) needs Java 25 or newer.\n"
                + "Downloads go\n"
                + "into %USERPROFILE%\\.jbang\\cache\\jdks\\<version>[-jre]\n"
                + "(same cache jbang itself uses for JDKs). Disable via .jrc: java.autoinstall=false\n\n"
                + "Examples:\n"
                + "  " + exeBaseName + ".exe myapp.jar\n"
                + "  " + exeBaseName + ".exe -Xjr:jvm=dll myapp.jar\n"
                + "  " + exeBaseName + ".exe -Xjr:create-config=myapp.jar\n"
                + "  " + exeBaseName + ".exe -Xjr:java.home=C:\\Java\\jdk21 myapp.jar --verbose";
        Ui.info(hasConsole, "Java Runner - Help", info);
    }
}
