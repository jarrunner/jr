package littlejlib.jr;

import static littlejlib.jr.N.*;

/** AOT cache path naming, staleness cleanup, and base52 encoding - mirrors launcher.c's equivalents. */
public final class AotCache {
    private AotCache() {}

    private static final String BASE52 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    /** "<dir>\<jarBaseName>.<sizeB52>.<modTimeB52>.aot", or "" if the jar's file info can't be read. */
    public static String buildCacheName(String jarPath) {
        var size = FileInfo.size(jarPath);
        var modTime = FileInfo.lastWriteTimeRaw(jarPath);
        if (size < 0 || modTime < 0) {
            return "";
        }
        var dir = Paths.dirOf(jarPath);
        var base = Paths.baseNameNoExt(jarPath);
        var name = base + "." + encodeBase52(size) + "." + encodeBase52(modTime) + ".aot";
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
