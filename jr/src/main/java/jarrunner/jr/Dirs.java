package jarrunner.jr;

import org.teavm.interop.Address;

import java.util.ArrayList;
import java.util.List;

import static jarrunner.jr.N.*;

/** Directory helpers for the auto-install pipeline: recursive mkdir, recursive delete, and
 *  finding the single top-level folder a Temurin zip extracts to - mirrors javainstall.c's
 *  ji* directory helpers. */
public final class Dirs {
    private Dirs() {}

    private static final int FIND_DATA_SIZE = WinOffsets.WIN32_FIND_DATAW.SIZE;
    private static final int FIND_DATA_FILENAME_MAX = WinApi.MAX_PATH; // cFileName is WCHAR[MAX_PATH]
    private static final int FILE_ATTRIBUTE_DIRECTORY = WinApi.FILE_ATTRIBUTE_DIRECTORY;

    /** Creates every missing path segment (segments after the drive-letter prefix). */
    public static void mkdirRecursive(String path) {
        var p = path.endsWith("\\") || path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (var i = 3; i < p.length(); i++) { // +3 skips "C:\" - always an absolute drive path here
            if (p.charAt(i) == '\\' || p.charAt(i) == '/') {
                WinApi.createDirectoryW(p.substring(0, i), NULL);
            }
        }
        WinApi.createDirectoryW(p, NULL);
    }

    /** Scoped per directory level, so a deep tree holds at most one level's names at a time. */
    public static void removeDirTree(String path) {
        memScoped(() -> {
            var attr = WinApi.getFileAttributesW(path);
            if (attr == WinApi.INVALID_FILE_ATTRIBUTES) {
                return;
            }
            if ((attr & FILE_ATTRIBUTE_DIRECTORY) == 0) {
                WinApi.deleteFileW(path);
                return;
            }
            var findData = alloc(FIND_DATA_SIZE);
            var findHandle = WinApi.findFirstFileW(path + "\\*", findData);
            if (findHandle.toLong() != 0 && findHandle != WinApi.INVALID_HANDLE_VALUE) {
                do {
                    var name = fileName(findData);
                    if (name.equals(".") || name.equals("..")) {
                        continue;
                    }
                    var childAttr = WinOffsets.WIN32_FIND_DATAW.dwFileAttributes(findData);
                    var child = path + "\\" + name;
                    if ((childAttr & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                        removeDirTree(child);
                    } else {
                        WinApi.setFileAttributesW(child, WinApi.FILE_ATTRIBUTE_NORMAL);
                        WinApi.deleteFileW(child);
                    }
                } while (WinApi.findNextFileW(findHandle, findData) != 0);
                WinApi.findClose(findHandle);
            }
            WinApi.removeDirectoryW(path);
        });
    }

    /** Adoptium zips contain exactly one top-level folder (e.g. "jdk-21.0.12.1+1"). Finds it so
     *  its contents can be promoted up to the jbang-style flattened cache slot. */
    public static String findSingleSubdir(String parentDir) {
        var names = listDirNames(parentDir);
        return names.isEmpty() ? null : names.get(0);
    }

    /** Directory entry names directly under parentDir (files excluded, "." and ".." excluded) -
     *  used by JavaInstall's "NN+" cached-JDK lookup, which needs to enumerate candidate versions. */
    public static List<String> listDirNames(String parentDir) {
        return memScoped(() -> {
            var out = new ArrayList<String>();
            var findData = alloc(FIND_DATA_SIZE);
            var findHandle = WinApi.findFirstFileW(parentDir + "\\*", findData);
            if (findHandle.toLong() == 0 || findHandle == WinApi.INVALID_HANDLE_VALUE) {
                return out;
            }
            do {
                var name = fileName(findData);
                if (!name.equals(".") && !name.equals("..")
                        && (WinOffsets.WIN32_FIND_DATAW.dwFileAttributes(findData) & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                    out.add(name);
                }
            } while (WinApi.findNextFileW(findHandle, findData) != 0);
            WinApi.findClose(findHandle);
            return out;
        });
    }

    /** PRP-31: names of the files directly in dir matching a wildcard pattern such as "app.*.aot". */
    static List<String> matching(String dir, String pattern) {
        return memScoped(() -> {
            var out = new ArrayList<String>();
            var findData = alloc(FIND_DATA_SIZE);
            var findHandle = WinApi.findFirstFileW(dir + "\\" + pattern, findData);
            if (findHandle.toLong() == 0 || findHandle == WinApi.INVALID_HANDLE_VALUE) {
                return out;
            }
            do {
                if ((WinOffsets.WIN32_FIND_DATAW.dwFileAttributes(findData) & FILE_ATTRIBUTE_DIRECTORY) == 0) {
                    out.add(fileName(findData));
                }
            } while (WinApi.findNextFileW(findHandle, findData) != 0);
            WinApi.findClose(findHandle);
            return out;
        });
    }

    static int matchCount(String dir, String pattern) {
        return dir == null || dir.isEmpty() ? 0 : matching(dir, pattern).size();
    }

    public static boolean moveDirectory(String src, String dst) {
        removeDirTree(dst); // clear any empty leftover from a previous partial attempt
        return WinApi.moveFileExW(src, dst, WinApi.MOVEFILE_COPY_ALLOWED) != 0;
    }

    static String fileName(Address findData) {
        return wstring(WinOffsets.WIN32_FIND_DATAW.cFileName(findData), FIND_DATA_FILENAME_MAX);
    }
}
