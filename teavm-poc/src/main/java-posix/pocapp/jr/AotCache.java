package pocapp.jr;

import static pocapp.jr.N.*;

/** AOT cache path naming, staleness cleanup, and base52 encoding - POSIX twin of the Windows
 *  AotCache, opendir/readdir/unlink in place of FindFirstFile/DeleteFileA, '/' in place of '\\'. */
public final class AotCache {
    private AotCache() {}

    private static final String BASE52 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    /** "<dir>/<jarBaseName>.<sizeB52>.<modTimeB52>.aot", or "" if the jar's file info can't be read. */
    public static String buildCacheName(String jarPath) {
        var size = FileInfo.size(jarPath);
        var modTime = FileInfo.lastWriteTimeRaw(jarPath);
        if (size < 0 || modTime < 0) {
            return "";
        }
        var dir = Paths.dirOf(jarPath);
        var base = Paths.baseNameNoExt(jarPath);
        var name = base + "." + encodeBase52(size) + "." + encodeBase52(modTime) + ".aot";
        return dir.isEmpty() ? name : dir + "/" + name;
    }

    public static void cleanupOldFiles(String jarPath, String currentCachePath) {
        var jarDir = Paths.dirOf(jarPath);
        var dir = jarDir.isEmpty() ? Cwd.get() : jarDir;
        var base = jarPath.isEmpty() ? "" : Paths.baseNameNoExt(jarPath);
        var currentFileName = Paths.fileNameOf(currentCachePath);
        if (base.isEmpty()) {
            return;
        }

        memScoped(() -> {
            var dh = PosixApi.opendir(cstr(dir));
            if (dh.toLong() == 0) {
                return;
            }
            var prefix = base + ".";
            for (var e = PosixApi.readdir(dh); e.toLong() != 0; e = PosixApi.readdir(dh)) {
                var name = string(e.add(PosixOffsets.dirent.d_name), 256);
                if (name.startsWith(prefix) && name.endsWith(".aot") && !name.equals(currentFileName)) {
                    var fullPath = dir + "/" + name;
                    PosixApi.unlink(cstr(fullPath));
                    Log.info("Cleaned up old AOT file: " + fullPath);
                }
            }
            PosixApi.closedir(dh);
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
