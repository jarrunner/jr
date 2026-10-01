package jarrunner.jr;

import static jarrunner.jr.N.*;

/** File size/mtime via stat(2). POSIX twin of the Windows FileInfo (GetFileAttributesExA-based). */
public final class FileInfo {
    private FileInfo() {}

    private static final int STRUCT_SIZE = PosixOffsets.stat_t.SIZE;

    /** st_mtim as (seconds * 1e9 + nanoseconds) - used only as a change-detection signal (not a
     *  calendar date, like the Windows FILETIME raw value), or -1 if the file cannot be stat'd. */
    public static long lastWriteTimeRaw(String path) {
        var buf = alloc(STRUCT_SIZE);
        if (PosixApi.stat(cstr(path), buf) != 0) {
            return -1;
        }
        var mtim = buf.add(PosixOffsets.stat_t.st_mtim);
        var sec = PosixOffsets.timespec.tv_sec(mtim);
        var nsec = PosixOffsets.timespec.tv_nsec(mtim);
        return sec * 1_000_000_000L + nsec;
    }

    /** File size in bytes, or -1 if the file cannot be stat'd. */
    public static long size(String path) {
        var buf = alloc(STRUCT_SIZE);
        if (PosixApi.stat(cstr(path), buf) != 0) {
            return -1;
        }
        return PosixOffsets.stat_t.st_size(buf);
    }
}
