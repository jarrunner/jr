package jarrunner.jr;

/** Little-endian fields read from a byte[], for formats no header declares (.ico directories, string tables,
 *  version blocks). Java arrays are bounds-checked, so a corrupt file throws instead of reading past its end. */
final class Le {
    private Le() {}

    static int u16(byte[] b, int i) { return b[i] & 0xFF | (b[i + 1] & 0xFF) << 8; }

    static int i32(byte[] b, int i) { return u16(b, i) | u16(b, i + 2) << 16; }
}
