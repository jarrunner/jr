package jarrunner.jr;

import static jarrunner.jr.N.*;

/** AOT cache path naming, staleness cleanup, and base52 encoding - mirrors launcher.c's equivalents. */
public final class AotCache {
    private AotCache() {}

    private static final String BASE52 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    /** PRP-31: identifies the JVM a cache belongs to, so a cache made by one Java is never handed to another. */
    static String jvmTag = "";
    static String lastPath = "";

    /** PRP-31: a cache the JVM may have refused is the cheapest cause to rule out when it fails to start. */
    static void deleteLast() {
        if (!lastPath.isEmpty() && WinApi.deleteFileA(N.cstr(lastPath)) != 0) {
            Log.info("Deleted AOT cache: " + lastPath);
        }
    }

    static String jvmTag(JavaHome java) {
        var id = java.releaseText != null ? java.releaseText : java.home + "|" + java.major;
        var h = 0xcbf29ce484222325L;
        for (var i = 0; i < id.length(); i++) {
            h = (h ^ id.charAt(i)) * 0x100000001b3L;
        }
        return encodeBase52(h >>> 34);
    }

    /** "<dir>\<jarBaseName>.<sizeB52>.<modTimeB52>.aot", or "" if the jar's file info can't be read. */
    public static String buildCacheName(String jarPath) {
        var size = FileInfo.size(jarPath);
        var modTime = FileInfo.lastWriteTimeRaw(jarPath);
        if (size < 0 || modTime < 0) {
            return "";
        }
        var dir = Paths.dirOf(jarPath);
        var base = Paths.baseNameNoExt(jarPath);
        var name = base + "." + encodeBase52(size) + "." + encodeBase52(modTime) + (jvmTag.isEmpty() ? "" : "." + jvmTag) + ".aot";
        return dir.isEmpty() ? name : dir + "\\" + name;
    }

    public static void cleanupOldFiles(String jarPath, String currentCachePath) {
        var jarDir = Paths.dirOf(jarPath);
        var dir = jarDir.isEmpty() ? Cwd.get() : jarDir;
        var base = Paths.baseNameNoExt(jarPath);
        var pattern = dir + "\\" + base + ".*.aot";
        var currentFileName = Paths.fileNameOf(currentCachePath);

        memScoped(() -> {
            var findData = alloc(WinOffsets.WIN32_FIND_DATAA.SIZE);
            var findHandle = WinApi.findFirstFileA(cstr(pattern), findData);
            if (findHandle.toLong() == 0 || findHandle == WinApi.INVALID_HANDLE_VALUE) {
                return;
            }
            do {
                var foundName = Dirs.fileName(findData);
                if (!AsciiStr.equalsIgnoreCase(foundName, currentFileName)) {
                    var fullPath = dir + "\\" + foundName;
                    WinApi.deleteFileA(cstr(fullPath));
                    Log.info("Cleaned up old AOT file: " + fullPath);
                }
            } while (WinApi.findNextFileA(findHandle, findData) != 0);
            WinApi.findClose(findHandle);
        });
    }

    static String encodeBase52(long value) {
        if (value == 0) {
            return String.valueOf(BASE52.charAt(0));
        }
        var sb = new StringBuilder();
        var v = value;
        while (v > 0) {
            sb.append(BASE52.charAt((int) (v % 52)));
            v /= 52;
        }
        return sb.reverse().toString();
    }
}
