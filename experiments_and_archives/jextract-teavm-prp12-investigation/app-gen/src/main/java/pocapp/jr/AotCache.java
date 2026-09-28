package pocapp.jr;

import org.teavm.interop.Address;

/** AOT cache path naming, staleness cleanup, and base52 encoding - mirrors launcher.c's equivalents. */
public final class AotCache {
    private AotCache() {}

    private static final String BASE52 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";
    private static final int FIND_DATA_SIZE = WinOffsets.WIN32_FIND_DATAA.SIZE;
    private static final int FIND_DATA_FILENAME_OFFSET = WinOffsets.WIN32_FIND_DATAA.cFileName;
    private static final int FIND_DATA_FILENAME_MAX = 260;

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
        var dir = Paths.dirOf(jarPath);
        if (dir.isEmpty()) {
            dir = Cwd.get();
        }
        var base = Paths.baseNameNoExt(jarPath);
        var pattern = dir + "\\" + base + ".*.aot";
        var currentFileName = Paths.fileNameOf(currentCachePath);

        var findData = new byte[FIND_DATA_SIZE];
        var findDataAddr = Address.ofData(findData);
        var findHandle = WinApi.findFirstFileA(Cstr.of(pattern), findDataAddr);
        if (findHandle.toLong() == 0 || findHandle == WinApi.INVALID_HANDLE_VALUE) {
            return;
        }
        do {
            var foundName = readFileName(findDataAddr);
            if (!AsciiStr.equalsIgnoreCase(foundName, currentFileName)) {
                var fullPath = dir + "\\" + foundName;
                WinApi.deleteFileA(Cstr.of(fullPath));
                Log.info("Cleaned up old AOT file: " + fullPath);
            }
        } while (WinApi.findNextFileA(findHandle, findDataAddr) != 0);
        WinApi.findClose(findHandle);
    }

    private static String readFileName(Address findDataAddr) {
        var sb = new StringBuilder();
        for (var i = 0; i < FIND_DATA_FILENAME_MAX; i++) {
            var b = findDataAddr.add(FIND_DATA_FILENAME_OFFSET + i).getByte();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
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
