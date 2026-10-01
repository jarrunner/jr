package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** Small file read/write helpers built on plain fopen/fread/fputs/fclose - no java.io involved.
 *  POSIX twin of the Windows FileIo; same method signatures so Config/Log/JavaFinder compile
 *  unchanged against either source tree. */
public final class FileIo {
    private FileIo() {}

    public static boolean exists(String path) {
        return PosixApi.access(cstr(path), PosixApi.F_OK) == 0;
    }

    public static String readAll(String path) {
        var size = FileInfo.size(path);
        if (size < 0) {
            return null;
        }
        var f = PosixApi.fopen(cstr(path), cstr("rb"));
        if (f.toLong() == 0) {
            return null;
        }
        var buf = alloc((int) size + 1);
        PosixApi.fread(buf, 1, size, f);
        PosixApi.fclose(f);
        var sb = new StringBuilder((int) size);
        for (var i = 0; i < size; i++) {
            sb.append((char) (buf.add(i).getByte() & 0xFF));
        }
        return sb.toString();
    }

    public record NativeFile(Address data, int size) {}

    public static NativeFile readAllNative(String path) {
        var size = FileInfo.size(path);
        if (size <= 0 || size > 64L * 1024 * 1024) {
            return null;
        }
        var f = PosixApi.fopen(cstr(path), cstr("rb"));
        if (f.toLong() == 0) {
            return null;
        }
        var buf = alloc((int) size);
        var got = PosixApi.fread(buf, 1, size, f);
        PosixApi.fclose(f);
        return got == size ? new NativeFile(buf, (int) size) : null;
    }

    public static boolean writeAll(String path, String content) {
        var f = PosixApi.fopen(cstr(path), cstr("w"));
        if (f.toLong() == 0) {
            return false;
        }
        PosixApi.fputs(cstr(content), f);
        PosixApi.fclose(f);
        return true;
    }

    public static boolean append(Address openFile, String content) {
        var r = memScoped(() -> PosixApi.fputs(cstr(content), openFile));
        PosixApi.fflush(openFile);
        return r >= 0;
    }
}
