package littlejlib.jr;

import org.teavm.interop.Address;

import static littlejlib.jr.N.*;

/** Small file read/write helpers built on plain fopen/fread/fputs/fclose - no java.io involved. */
public final class FileIo {
    private FileIo() {}

    public static boolean exists(String path) {
        return WinApi.getFileAttributesA(cstr(path)) != WinApi.INVALID_FILE_ATTRIBUTES;
    }

    public static String readAll(String path) {
        var size = FileInfo.size(path);
        if (size < 0) {
            return null;
        }
        var f = WinApi.fopen(cstr(path), cstr("rb"));
        if (f.toLong() == 0) {
            return null;
        }
        var buf = alloc((int) size + 1);
        WinApi.fread(buf, 1, size, f);
        WinApi.fclose(f);
        var sb = new StringBuilder((int) size);
        for (var i = 0; i < size; i++) {
            sb.append((char) (buf.add(i).getByte() & 0xFF));
        }
        return sb.toString();
    }

    public record NativeFile(Address data, int size) {}

    /** Reads a whole file into a native buffer, unconverted - for binary content (icons, raw
     *  resource files, certificates) where readAll's byte-per-char String would corrupt anything
     *  that is not plain ASCII text. Null if the file cannot be opened, or is empty or over 64 MB -
     *  mirrors resedit.c's reReadFileAlloc, used by the resource-editing port (PRP-20 phase 2). */
    public static NativeFile readAllNative(String path) {
        var size = FileInfo.size(path);
        if (size <= 0 || size > 64L * 1024 * 1024) {
            return null;
        }
        var f = WinApi.fopen(cstr(path), cstr("rb"));
        if (f.toLong() == 0) {
            return null;
        }
        var buf = alloc((int) size);
        var got = WinApi.fread(buf, 1, size, f);
        WinApi.fclose(f);
        return got == size ? new NativeFile(buf, (int) size) : null;
    }

    public static boolean writeAll(String path, String content) {
        var f = WinApi.fopen(cstr(path), cstr("w"));
        if (f.toLong() == 0) {
            return false;
        }
        WinApi.fputs(cstr(content), f);
        WinApi.fclose(f);
        return true;
    }

    public static boolean append(Address openFile, String content) {
        var r = memScoped(() -> WinApi.fputs(cstr(content), openFile));
        WinApi.fflush(openFile);
        return r >= 0;
    }
}
