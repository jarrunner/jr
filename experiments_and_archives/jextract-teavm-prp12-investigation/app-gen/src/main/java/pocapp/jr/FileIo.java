package pocapp.jr;

import org.teavm.interop.Address;

/** Small file read/write helpers built on plain fopen/fread/fputs/fclose - no java.io involved. */
public final class FileIo {
    private FileIo() {}

    public static boolean exists(String path) {
        return WinApi.getFileAttributesA(Cstr.of(path)) != WinApi.INVALID_FILE_ATTRIBUTES;
    }

    public static String readAll(String path) {
        var size = FileInfo.size(path);
        if (size < 0) {
            return null;
        }
        var f = WinApi.fopen(Cstr.of(path), Cstr.of("rb"));
        if (f.toLong() == 0) {
            return null;
        }
        var buf = new byte[(int) size + 1];
        var addr = org.teavm.interop.Address.ofData(buf);
        WinApi.fread(addr, 1, size, f);
        WinApi.fclose(f);
        var sb = new StringBuilder((int) size);
        for (var i = 0; i < size; i++) {
            sb.append((char) (buf[i] & 0xFF));
        }
        return sb.toString();
    }

    public static boolean writeAll(String path, String content) {
        var f = WinApi.fopen(Cstr.of(path), Cstr.of("w"));
        if (f.toLong() == 0) {
            return false;
        }
        WinApi.fputs(Cstr.of(content), f);
        WinApi.fclose(f);
        return true;
    }

    public static boolean append(Address openFile, String content) {
        var r = WinApi.fputs(Cstr.of(content), openFile);
        WinApi.fflush(openFile);
        return r >= 0;
    }
}
