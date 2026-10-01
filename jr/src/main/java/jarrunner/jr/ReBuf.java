package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/**
 * A growable native byte buffer for building a VS_VERSIONINFO block, mirroring resedit.c's ReBuf -
 * except resedit.c's version block is capped at 65535 bytes (its own check, "Version information too
 * large"), so this allocates that cap up front rather than reimplementing realloc; N/Arena's own
 * program-lifetime region already outlives the single UpdateResourceW call that consumes it.
 */
final class ReBuf {
    private static final int CAP = 65536;

    final Address base = alloc(CAP);
    int len;

    void putShort(short v) {
        base.add(len).putShort(v);
        len += 2;
    }

    void putInt(int v) {
        base.add(len).putInt(v);
        len += 4;
    }

    /** Copies size bytes from a native struct (e.g. a VS_FIXEDFILEINFO scratch buffer) verbatim. */
    void putStruct(Address src, int size) {
        for (var i = 0; i < size; i++) {
            base.add(len + i).putByte(src.add(i).getByte());
        }
        len += size;
    }

    /** UTF-16LE, NUL-terminated - a version-block "key". */
    void putWideStringZ(String s) {
        for (var i = 0; i < s.length(); i++) {
            putShort((short) s.charAt(i));
        }
        putShort((short) 0);
    }

    void align4() {
        while (len % 4 != 0) {
            base.add(len).putByte((byte) 0);
            len++;
        }
    }

    /** A version-block node: wLength (patched by end()), wValueLength, wType, key, padding, then
     *  whatever the caller writes for the value - mirrors reNodeBegin. valueLenUnits is in WCHARs
     *  for text (type 1) and bytes for binary (type 0); pass 0/skip writing when there is no value
     *  (a container node like StringFileInfo/VarFileInfo/the translation table). */
    int begin(String key, int valueLenUnits, int type) {
        align4();
        var start = len;
        putShort((short) 0); // wLength placeholder, patched in end()
        putShort((short) valueLenUnits);
        putShort((short) type);
        putWideStringZ(key);
        align4();
        return start;
    }

    void end(int start) {
        var nodeLen = (short) (len - start);
        base.add(start).putShort(nodeLen);
    }
}
