package pocapp.jr;

import org.teavm.interop.Address;

/** Directory helpers for the auto-install pipeline: recursive mkdir, recursive delete, and
 *  finding the single top-level folder a Temurin zip extracts to - mirrors javainstall.c's
 *  ji* directory helpers. */
public final class Dirs {
    private Dirs() {}

    private static final int FIND_DATA_SIZE = WinOffsets.WIN32_FIND_DATAA.SIZE;
    private static final int FIND_DATA_FILENAME_OFFSET = WinOffsets.WIN32_FIND_DATAA.cFileName;
    private static final int FIND_DATA_ATTR_OFFSET = WinOffsets.WIN32_FIND_DATAA.dwFileAttributes;
    private static final int FIND_DATA_FILENAME_MAX = 260;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

    /** Creates every missing path segment (segments after the drive-letter prefix). */
    public static void mkdirRecursive(String path) {
        var p = path.endsWith("\\") || path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (var i = 3; i < p.length(); i++) { // +3 skips "C:\" - always an absolute drive path here
            if (p.charAt(i) == '\\' || p.charAt(i) == '/') {
                WinApi.createDirectoryA(Cstr.of(p.substring(0, i)), Address.fromInt(0));
            }
        }
        WinApi.createDirectoryA(Cstr.of(p), Address.fromInt(0));
    }

    public static void removeDirTree(String path) {
        var attr = WinApi.getFileAttributesA(Cstr.of(path));
        if (attr == WinApi.INVALID_FILE_ATTRIBUTES) {
            return;
        }
        if ((attr & FILE_ATTRIBUTE_DIRECTORY) == 0) {
            WinApi.deleteFileA(Cstr.of(path));
            return;
        }

        var findData = new byte[FIND_DATA_SIZE];
        var findDataAddr = Address.ofData(findData);
        var findHandle = WinApi.findFirstFileA(Cstr.of(path + "\\*"), findDataAddr);
        if (findHandle.toLong() != 0 && findHandle != WinApi.INVALID_HANDLE_VALUE) {
            do {
                var name = readFileName(findDataAddr);
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                var childAttr = findDataAddr.add(FIND_DATA_ATTR_OFFSET).getInt();
                var child = path + "\\" + name;
                if ((childAttr & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                    removeDirTree(child);
                } else {
                    WinApi.setFileAttributesA(Cstr.of(child), WinApi.FILE_ATTRIBUTE_NORMAL);
                    WinApi.deleteFileA(Cstr.of(child));
                }
            } while (WinApi.findNextFileA(findHandle, findDataAddr) != 0);
            WinApi.findClose(findHandle);
        }
        WinApi.removeDirectoryA(Cstr.of(path));
    }

    /** Adoptium zips contain exactly one top-level folder (e.g. "jdk-21.0.12.1+1"). Finds it so
     *  its contents can be promoted up to the jbang-style flattened cache slot. */
    public static String findSingleSubdir(String parentDir) {
        var findData = new byte[FIND_DATA_SIZE];
        var findDataAddr = Address.ofData(findData);
        var findHandle = WinApi.findFirstFileA(Cstr.of(parentDir + "\\*"), findDataAddr);
        if (findHandle.toLong() == 0 || findHandle == WinApi.INVALID_HANDLE_VALUE) {
            return null;
        }
        String found = null;
        do {
            var name = readFileName(findDataAddr);
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            var attr = findDataAddr.add(FIND_DATA_ATTR_OFFSET).getInt();
            if ((attr & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                found = name;
                break;
            }
        } while (WinApi.findNextFileA(findHandle, findDataAddr) != 0);
        WinApi.findClose(findHandle);
        return found;
    }

    public static boolean moveDirectory(String src, String dst) {
        removeDirTree(dst); // clear any empty leftover from a previous partial attempt
        return WinApi.moveFileExA(Cstr.of(src), Cstr.of(dst), WinApi.MOVEFILE_COPY_ALLOWED) != 0;
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
}
