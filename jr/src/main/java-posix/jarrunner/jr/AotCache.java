package jarrunner.jr;

import static jarrunner.jr.N.*;

/** AOT cache path naming, staleness cleanup, and base52 encoding - POSIX twin of the Windows
 *  AotCache, opendir/readdir/unlink in place of FindFirstFile/DeleteFileA, '/' in place of '\\'. */
public final class AotCache {
    private AotCache() {}

    private static final String BASE52 = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    private static final int W_OK = 2; // access(2): the same value on Linux and macOS

    /** {folder, file-name prefix} for the jar's cache. Beside the jar, as on Windows, unless that folder is inside an
     *  .app bundle (an app must not write into itself, and often cannot) or is not writable; then the user's cache
     *  folder (Os.userCacheDir()/jr/aot), where a hash of the jar's folder joins the name so that two apps' jars of
     *  the same name never share a cache or delete each other's in cleanupOldFiles (PRP-36). */
    private static String[] where(String jarPath) {
        var jarDir = Paths.dirOf(jarPath);
        var dir = jarDir.isEmpty() ? Cwd.get() : jarDir;
        var base = Paths.baseNameNoExt(jarPath);
        if (!dir.contains(".app/") && PosixApi.access(dir, W_OK) == 0) {
            return new String[] {dir, base + "."};
        }
        var cache = Os.userCacheDir();
        if (cache == null) {
            return new String[] {dir, base + "."};
        }
        var shared = cache + "/jr/aot";
        for (var i = 1; i <= shared.length(); i++) {
            if (i == shared.length() || shared.charAt(i) == '/') PosixApi.mkdir(shared.substring(0, i), (short) 0755);
        }
        var h = 0xcbf29ce484222325L;
        for (var i = 0; i < dir.length(); i++) {
            h = (h ^ dir.charAt(i)) * 0x100000001b3L;
        }
        return new String[] {shared, base + "." + encodeBase52(h >>> 34) + "."};
    }

    /** "<dir>/<prefix><sizeB52>.<modTimeB52>.aot" (see where), or "" if the jar's file info can't be read. */
    public static String buildCacheName(String jarPath) {
        var size = FileInfo.size(jarPath);
        var modTime = FileInfo.lastWriteTimeRaw(jarPath);
        if (size < 0 || modTime < 0) {
            return "";
        }
        var w = where(jarPath);
        var name = w[1] + encodeBase52(size) + "." + encodeBase52(modTime) + ".aot";
        return w[0].isEmpty() ? name : w[0] + "/" + name;
    }

    public static void cleanupOldFiles(String jarPath, String currentCachePath) {
        if (jarPath.isEmpty() || Paths.baseNameNoExt(jarPath).isEmpty()) {
            return;
        }
        var w = where(jarPath);
        var dir = w[0];
        var currentFileName = Paths.fileNameOf(currentCachePath);

        memScoped(() -> {
            var dh = PosixApi.opendir(dir);
            if (dh.toLong() == 0) {
                return;
            }
            var prefix = w[1];
            for (var e = PosixApi.readdir(dh); e.toLong() != 0; e = PosixApi.readdir(dh)) {
                var name = string(PosixOffsets.dirent.d_name(e), 256);
                if (name.startsWith(prefix) && name.endsWith(".aot") && !name.equals(currentFileName)) {
                    var fullPath = dir + "/" + name;
                    PosixApi.unlink(fullPath);
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
