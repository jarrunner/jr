package pocapp.jr;

import org.teavm.interop.Address;

/** This executable's own path/name via GetModuleFileNameA - used to find the sibling .jrc file
 *  and for display purposes, mirroring launcher.c's getExeBaseName/getExeFullPathWithoutExt. */
public final class ExeInfo {
    private ExeInfo() {}

    public static String fullPathNoExt() {
        return stripExeExt(fullPath());
    }

    public static String baseNameNoExt() {
        return stripExeExt(Paths.fileNameOf(fullPath()));
    }

    private static String fullPath() {
        var buf = new byte[1024];
        var addr = Address.ofData(buf);
        var len = WinApi.getModuleFileNameA(Address.fromInt(0), addr, buf.length);
        var sb = new StringBuilder();
        for (var i = 0; i < len; i++) {
            sb.append((char) (buf[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String stripExeExt(String path) {
        return AsciiStr.endsWithIgnoreCase(path, ".exe") ? path.substring(0, path.length() - 4) : path;
    }
}
