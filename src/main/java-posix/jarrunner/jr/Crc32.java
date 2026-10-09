package jarrunner.jr;

/** CRC32 (the zip/IEEE polynomial) of a file, in plain Java: JarCheck's per-launch check that a downloaded jar is
 *  unchanged. The Windows build calls ntdll's RtlComputeCrc32 for the same value. */
public final class Crc32 extends FileChunks {
    private static final int[] TABLE = new int[256];
    static {
        for (var n = 0; n < 256; n++) {
            var c = n;
            for (var k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            TABLE[n] = c;
        }
    }
    private static final String HEX = "0123456789abcdef";

    private int crc = 0xFFFFFFFF;

    private Crc32() {}

    /** Lowercase hex, 8 digits (the form the maven plugin writes as jar.crc32), or null if the file cannot be read. */
    public static String ofFile(String path) {
        var s = new Crc32();
        if (!s.readFile(path)) return null;
        var v = ~s.crc;
        var sb = new StringBuilder(8);
        for (var i = 28; i >= 0; i -= 4) sb.append(HEX.charAt((v >>> i) & 0xF));
        return sb.toString();
    }

    @Override
    void update(byte[] b) {
        var c = crc;
        for (var x : b) c = TABLE[(c ^ x) & 0xFF] ^ (c >>> 8);
        crc = c;
    }
}
