package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** macOS version of Os (see src/main/java-posix/jarrunner/jr/Os.java), swapped in by
 *  build-macos.sh. Needs the macOS bindings: nsGetExecutablePath is bound only from
 *  jr-posix-macos.h / posix-macos.symbols. */
final class Os {
    private Os() {}

    /** The modification time inside a struct stat: macOS names it st_mtimespec. */
    static Address statMtime(@Returned Address stat) {
        return PosixOffsets.stat_t.st_mtimespec(stat);
    }

    /** This executable's resolved path, or "" if it cannot be found. macOS has no /proc, so this
     *  asks dyld. _NSGetExecutablePath returns -1 and stores the size it needs when the buffer is
     *  too small; one retry at that size. Its answer may still contain symlinks, so it then goes
     *  through realpath, as the Linux /proc/self/exe answer effectively does. */
    static String exePath() {
        var size = intVar();
        setInt(size, PosixApi.PATH_MAX);
        var buf = alloc(PosixApi.PATH_MAX);
        if (PosixApi.nsGetExecutablePath(buf, size) != 0) {
            buf = alloc(intOf(size));
            if (PosixApi.nsGetExecutablePath(buf, size) != 0) {
                return "";
            }
        }
        var resolved = PosixApi.realpath(buf, alloc(PosixApi.PATH_MAX));
        return resolved.toLong() == 0 ? string(buf) : string(resolved);
    }

    /** The app config the maven plugin wrote into this binary (PRP-36), or null if there is none. build-macos.sh
     *  links an empty (all-zero) __DATA,__jrc section into every jr; the plugin fills it with the config text,
     *  NUL-terminated, and re-signs. dyld maps it with the rest of the image, so this reads memory, not the file. */
    static String embeddedConfig() {
        var header = PosixApi.dyldGetImageHeader(0);
        if (header.toLong() == 0) {
            return null;
        }
        var size = longVar();
        var data = PosixApi.getsectiondata(header64(header), utf8("__DATA"), utf8("__jrc"), size);
        if (data.toLong() == 0 || longOf(size) <= 0) {
            return null;
        }
        var text = string(data, (int) longOf(size));
        return text.isEmpty() ? null : text;
    }

    /** Inside an .app (this binary in X.app/Contents/MacOS/), the Dock name and icon for the java child process,
     *  which would otherwise appear as "java" with Java's own icon. The icon is the one jr-maven-plugin puts in
     *  Contents/Resources/&lt;binary name&gt;.icns. Nothing outside a bundle. */
    static void bundleVmArgs(java.util.List<String> out) {
        var exe = exePath();
        var at = exe.lastIndexOf(".app/Contents/MacOS/");
        if (at < 0) {
            return;
        }
        var bundle = exe.substring(0, at + 4);
        out.add("-Xdock:name=" + Paths.fileNameOf(bundle.substring(0, at)));
        var icns = bundle + "/Contents/Resources/" + ExeInfo.baseNameNoExt() + ".icns";
        if (FileIo.exists(icns)) {
            out.add("-Xdock:icon=" + icns);
        }
    }

    @Unsafe("dyld declares every image header as struct mach_header; jr is built only as 64-bit, where the main "
            + "image's header is a struct mach_header_64, which is what getsectiondata takes")
    private static @CType("struct mach_header_64") Address header64(@Returned @CType("struct mach_header") Address header) {
        return header;
    }
}
