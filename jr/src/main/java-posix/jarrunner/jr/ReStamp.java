package jarrunner.jr;

/**
 * POSIX stand-in for the Windows ReStamp (PE resource-editing/signing, PRP-13/PRP-20 phase 2).
 * There is no PE format on Linux/macOS, so this jr build has nothing to do with -Xjr:make/edit/
 * icon/version/manifest/sign/... - out of scope per CLAUDE.md's platform decision and PRP-21's
 * scope note. Recognising the option names and refusing them with a clear message (rather than
 * silently ignoring them, or not recognising them at all and reporting "unknown option") is the
 * point of keeping this class's shape identical to the Windows one: JrOptions.parse needs no
 * change to build on either platform.
 */
public final class ReStamp {
    public String error;

    public int parseOption(String opt) {
        var key = opt.indexOf('=') < 0 ? opt : opt.substring(0, opt.indexOf('='));
        switch (AsciiStr.lower(key)) {
            case "make", "edit", "list-resources", "icon", "version", "manifest", "execution-level",
                    "sign", "sign.thumbprint", "sign.timestamp" -> {
                error = "-Xjr:" + key + " edits a Windows .exe's resources and has no meaning here "
                        + "(this is the Linux/macOS build of jr).";
                return -1;
            }
            default -> {
                if (key.startsWith("version.") || key.startsWith("string.") || key.startsWith("resource.")) {
                    error = "-Xjr:" + key + " edits a Windows .exe's resources and has no meaning here "
                            + "(this is the Linux/macOS build of jr).";
                    return -1;
                }
                return 0;
            }
        }
    }

    public boolean hasAction() { return false; }
    public boolean hasEdits() { return false; }
}
