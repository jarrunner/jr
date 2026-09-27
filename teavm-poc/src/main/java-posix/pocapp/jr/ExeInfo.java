package pocapp.jr;

import static pocapp.jr.N.*;

/** This executable's own path/name, used to find the sibling .jrc file and for display purposes -
 *  mirrors launcher.c's getExeBaseName/getExeFullPathWithoutExt (and the Windows ExeInfo's
 *  GetModuleFileNameA). LINUX ONLY for now: realpath("/proc/self/exe") - macOS has no /proc and
 *  needs _NSGetExecutablePath instead (bound in PosixApi when built against jr-posix-macos.h /
 *  posix-macos.symbols; this class needs a macOS-specific twin when that build is done - see
 *  PRP-21's status file). */
public final class ExeInfo {
    private ExeInfo() {}

    public static String fullPathNoExt() {
        return stripExt(fullPath());
    }

    public static String baseNameNoExt() {
        return stripExt(Paths.fileNameOf(fullPath()));
    }

    public static String fullPath() {
        var buf = alloc(PosixApi.PATH_MAX);
        var p = PosixApi.realpath(cstr("/proc/self/exe"), buf);
        return p.toLong() == 0 ? "" : string(buf);
    }

    /** No .exe extension to strip on POSIX - kept as a no-op so callers need no #ifdef. */
    private static String stripExt(String path) {
        return path;
    }
}
