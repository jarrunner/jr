package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** Small file read/write helpers built on plain fopen/fread/fputs/fclose - no java.io involved.
 *  POSIX twin of the Windows FileIo; same method signatures so Config/Log/JavaFinder compile
 *  unchanged against either source tree. */
public final class FileIo {
    private FileIo() {}

    public static boolean exists(String path) {
        return PosixApi.access(path, PosixApi.F_OK) == 0;
    }

    public static String readAll(String path) {
        var size = FileInfo.size(path);
        if (size < 0) {
            return null;
        }
        return memScoped(() -> { // the read buffer lives only for this call (PRP-35)
            var f = PosixApi.fopen(path, "rb");
            if (f.toLong() == 0) {
                return null;
            }
            var buf = alloc((int) size + 1);
            var got = PosixApi.fread(buf, 1, size, f);
            PosixApi.fclose(f);
            return text(buf, (int) got); // UTF-8, a BOM dropped (PRP-34)
        });
    }

    public static boolean writeAll(String path, String content) {
        var f = PosixApi.fopen(path, "w");
        if (f.toLong() == 0) {
            return false;
        }
        PosixApi.fputs(content, f);
        PosixApi.fclose(f);
        return true;
    }

    public static boolean append(Address openFile, String content) {
        var r = PosixApi.fputs(content, openFile);
        PosixApi.fflush(openFile);
        return r >= 0;
    }
}
