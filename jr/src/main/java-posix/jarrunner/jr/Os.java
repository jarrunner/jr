package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** The few things Linux and macOS spell differently, kept in one small class so the rest of the
 *  POSIX tree stays shared. This is the Linux version; build-macos.sh swaps in
 *  src/main/java-macos/jarrunner/jr/Os.java in its place. */
final class Os {
    private Os() {}

    /** The modification time inside a struct stat: Linux names it st_mtim. */
    static Address statMtime(@Returned Address stat) {
        return PosixOffsets.stat_t.st_mtim(stat);
    }

    /** This executable's resolved path, or "" if it cannot be found. */
    static String exePath() {
        var buf = alloc(PosixApi.PATH_MAX);
        var p = PosixApi.realpath(utf8("/proc/self/exe"), buf);
        return p.toLong() == 0 ? "" : string(buf);
    }

    /** The app config embedded in this binary (PRP-36), or null. Only the macOS build has an embedded config
     *  so far (a __DATA,__jrc section); on Linux it is always null and a sibling .jrc is read. */
    static String embeddedConfig() {
        return null;
    }

    /** macOS only (the Dock name and icon inside an .app); nothing on Linux. */
    static void bundleVmArgs(java.util.List<String> out) {
    }
}
