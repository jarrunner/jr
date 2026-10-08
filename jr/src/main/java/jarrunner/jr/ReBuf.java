package jarrunner.jr;

import java.util.Arrays;

/**
 * A growable little-endian byte buffer for building resource data (a VS_VERSIONINFO block, an icon group, a
 * string table), mirroring resedit.c's ReBuf. It is a Java array, so every write is bounds-checked and the data
 * needs no scope; it is copied to native memory only for UpdateResourceW (PRP-35).
 */
final class ReBuf {
    byte[] buf = new byte[256];
    int len;

    private void room(int n) {
        if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
    }

    void putByte(int v) {
        room(1);
        buf[len++] = (byte) v;
    }

    void putShort(int v) {
        putByte(v);
        putByte(v >> 8);
    }

    void putInt(int v) {
        putShort(v);
        putShort(v >>> 16);
    }

    void put(byte[] b) {
        room(b.length);
        System.arraycopy(b, 0, buf, len, b.length);
        len += b.length;
    }

    /** UTF-16LE, NUL-terminated - a version-block "key". */
    void putWideStringZ(String s) {
        for (var i = 0; i < s.length(); i++) {
            putShort(s.charAt(i));
        }
        putShort(0);
    }

    void align4() {
        while (len % 4 != 0) {
            putByte(0);
        }
    }

    /** A version-block node: wLength (patched by end()), wValueLength, wType, key, padding, then
     *  whatever the caller writes for the value - mirrors reNodeBegin. valueLenUnits is in WCHARs
     *  for text (type 1) and bytes for binary (type 0); pass 0/skip writing when there is no value
     *  (a container node like StringFileInfo/VarFileInfo/the translation table). */
    int begin(String key, int valueLenUnits, int type) {
        align4();
        var start = len;
        putShort(0); // wLength placeholder, patched in end()
        putShort(valueLenUnits);
        putShort(type);
        putWideStringZ(key);
        align4();
        return start;
    }

    void end(int start) {
        var nodeLen = len - start;
        buf[start] = (byte) nodeLen;
        buf[start + 1] = (byte) (nodeLen >> 8);
    }

    byte[] bytes() {
        return Arrays.copyOf(buf, len);
    }
}
