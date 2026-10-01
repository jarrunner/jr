package jarrunner.jr;

import static jarrunner.jr.N.*;

/** CRC32 of a file through ntdll's RtlComputeCrc32: the check jr repeats on every launch to see that
 *  a jar is still the one it verified by SHA-256 when it was downloaded (PRP-30). Measured on an
 *  82 MB jar: ~40 ms against ~120 ms for SHA-256 and ~25 ms to merely read the file. It catches any
 *  accidental or ordinary change; it is not proof against a deliberately forged file. */
public final class Crc32 {
    private Crc32() {}

    private static final int READ_CHUNK = 1 << 20;
    private static final String HEX = "0123456789abcdef";

    /** Lowercase 8-hex-digit CRC32 of the file, or null on any failure. */
    public static String ofFile(String path) {
        var ntdll = WinApi.getModuleHandleA(cstr("ntdll.dll"));
        var fn = ntdll.toLong() == 0 ? NULL : WinApi.getProcAddress(ntdll, cstr("RtlComputeCrc32"));
        if (fn.toLong() == 0) {
            return null;
        }
        var crc32 = (RtlComputeCrc32Fn) (Object) fn;
        return memScoped(() -> {
            var f = WinApi.fopen(cstr(path), cstr("rb"));
            if (f.toLong() == 0) {
                return null;
            }
            var buf = alloc(READ_CHUNK);
            var crc = 0;
            while (true) {
                var n = WinApi.fread(buf, 1, READ_CHUNK, f);
                if (n <= 0) {
                    break;
                }
                crc = crc32.invoke(crc, buf, (int) n);
            }
            WinApi.fclose(f);
            var sb = new StringBuilder(8);
            for (var shift = 28; shift >= 0; shift -= 4) {
                sb.append(HEX.charAt((crc >>> shift) & 0xF));
            }
            return sb.toString();
        });
    }
}
