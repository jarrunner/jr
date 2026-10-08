package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** Small file read/write helpers built on _wfopen/fread/fputs/fclose - no java.io involved. Paths are UTF-16
 *  (_wfopen), text is UTF-8 on disk (PRP-34). */
public final class FileIo {
    private FileIo() {}

    public static boolean exists(String path) {
        return WinApi.getFileAttributesW(path) != WinApi.INVALID_FILE_ATTRIBUTES;
    }

    /** _wfopen; mode is a CRT mode string ("rb", "a"), so it is ASCII. */
    public static Address open(String path, String mode) {
        return WinApi.wfopen(path, mode);
    }

    /** The file as text (see {@link N#text}: UTF-8, BOM dropped, ANSI code page if not UTF-8), or null. */
    public static String readAll(String path) {
        var b = read(path, true);
        return b == null ? null : text(b);
    }

    /** Files read onto the Java heap are capped here: TeaVM's heap is 32 MB (BuildDriver), and a parser may hold a
     *  second copy. Bigger payloads (raw resources, up to 64 MB) go off-heap through {@link #readFully}. */
    public static final int MAX_HEAP_READ = 8 << 20;

    /** A whole file's bytes, unconverted - for binary content parsed in Java (icons, certificates). Null if the file
     *  cannot be read, or is empty or over MAX_HEAP_READ. */
    public static byte[] readAllBytes(String path) {
        return read(path, false);
    }

    /** The native read buffer lives only for this call, so reading a file never leaves memory behind (PRP-35). */
    private static byte[] read(String path, boolean emptyOk) {
        var size = FileInfo.size(path);
        if (size < 0 || (size == 0 && !emptyOk) || size > MAX_HEAP_READ) {
            return null;
        }
        return memScoped(() -> {
            var buf = alloc((int) size + 1);
            return readFully(path, buf, (int) size) ? bytesOf(buf, (int) size) : null;
        });
    }

    /** Reads exactly size bytes of the file into dst, which must hold that many. False if it cannot. */
    public static boolean readFully(String path, Address dst, int size) {
        var f = open(path, "rb");
        if (f.toLong() == 0) {
            return false;
        }
        var got = WinApi.fread(dst, 1, size, f);
        WinApi.fclose(f);
        return got == size;
    }

    public static boolean writeAll(String path, String content) {
        var f = open(path, "w");
        if (f.toLong() == 0) {
            return false;
        }
        append(f, content);
        WinApi.fclose(f);
        return true;
    }

    public static boolean append(Address openFile, String content) {
        var r = WinApi.fputs(content, openFile);
        WinApi.fflush(openFile);
        return r >= 0;
    }
}
