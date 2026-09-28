package pocapp.jr;

/** The diagnostic/help text shown when jr is run with no jar argument, mirroring launcher.c. */
public final class Help {
    private Help() {}

    public static void show(boolean hasConsole, String exeBaseName, String javaExeName, String javaPath,
            String configPath, int useJvmDll) {
        var info = "Java Runner (jr) - Smart Java Launcher\n\n"
                + "Execution Context: " + (hasConsole ? "Console (terminal/cmd)" : "GUI (double-clicked)") + "\n"
                + "Launch Mode: " + (useJvmDll == 1 ? "in-process (jvm.dll)" : "child process (java.exe)") + "\n"
                + "Java Executable: " + javaExeName + "\n"
                + "Java Location: " + javaPath + "\n"
                + "Config File: " + configPath + " (not found)\n\n"
                + "Usage:\n"
                + "  " + exeBaseName + ".exe <jar-file> [args...]\n"
                + "  " + exeBaseName + ".exe --create-config [jar-file]\n"
                + "  " + exeBaseName + ".exe --java-home=PATH <jar-file> [args...]\n\n"
                + "Flags:\n"
                + "  --jvm-dll        run the JVM inside this process (unique process name)\n"
                + "  --jvm-exe        spawn java.exe as a child process (default)\n"
                + "  --enable-aot / --disable-aot\n"
                + "  --java-home=PATH\n"
                + "  --yes            don't ask before auto-installing a missing Java\n\n"
                + "If Java is not found, jr offers to download a matching Eclipse Temurin\n"
                + "JDK into %USERPROFILE%\\.jbang\\cache\\jdks\\<version> (same cache jbang\n"
                + "itself uses). Configure via .jrc: java.version=NN, java.autoinstall=false\n\n"
                + "Examples:\n"
                + "  " + exeBaseName + ".exe myapp.jar\n"
                + "  " + exeBaseName + ".exe --jvm-dll myapp.jar\n"
                + "  " + exeBaseName + ".exe --create-config myapp.jar\n"
                + "  " + exeBaseName + ".exe --java-home=C:\\Java\\jdk21 myapp.jar --verbose";
        Ui.info(hasConsole, "Java Runner - Help", info);
    }
}
