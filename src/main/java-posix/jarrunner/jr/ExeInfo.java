package jarrunner.jr;

import static jarrunner.jr.N.*;

/** This executable's own path/name, used to find the sibling .jrc file and for display purposes -
 *  mirrors launcher.c's getExeBaseName/getExeFullPathWithoutExt (and the Windows ExeInfo's
 *  GetModuleFileNameA). The lookup itself differs per OS and lives in Os.exePath(). */
public final class ExeInfo {
    private ExeInfo() {}

    public static String fullPathNoExt() {
        return stripExt(fullPath());
    }

    public static String baseNameNoExt() {
        return stripExt(Paths.fileNameOf(fullPath()));
    }

    public static String fullPath() {
        return Os.exePath();
    }

    /** No .exe extension to strip on POSIX - kept as a no-op so callers need no #ifdef. */
    private static String stripExt(String path) {
        return path;
    }
}
