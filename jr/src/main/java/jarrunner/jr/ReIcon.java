package jarrunner.jr;

import org.teavm.interop.Address;

/**
 * (i) Icon: an .ico file is a directory plus images; in an exe the images become RT_ICON resources
 * and the directory an RT_GROUP_ICON that points at them by id. The FIRST group is the one Explorer
 * and the taskbar show, so that is the one replaced - under its own name, its old images deleted
 * with it. Mirrors resedit.c's reQueueIcon.
 */
public final class ReIcon {
    private ReIcon() {}

    // Group-icon-directory entry as it appears INSIDE an RT_GROUP_ICON resource (14 bytes, 2-byte
    // packed) and the .ico FILE's own directory entry (16 bytes, naturally unpadded) - neither is a
    // real WinAPI struct any header declares, so both are read/built at fixed byte offsets, the same
    // way resedit.c's own #pragma pack(push, 2) declaration does.
    private static final int GROUP_ENTRY_SIZE = 14;
    private static final int FILE_ENTRY_SIZE = 16;

    public static void queue(Address module, ReEntries list, String icoPath, StringBuilder report) {
        var ico = FileIo.readAllBytes(icoPath);
        if (ico == null) {
            throw new ReError("Cannot read icon file: " + icoPath);
        }
        var icoSize = ico.length;
        if (icoSize < 6) {
            throw new ReError("Not a valid .ico file: " + icoPath);
        }
        var reserved = Le.u16(ico, 0);
        var type = Le.u16(ico, 2);
        var count = Le.u16(ico, 4);
        if (reserved != 0 || type != 1 || count == 0 || 6 + count * FILE_ENTRY_SIZE > icoSize) {
            throw new ReError("Not a valid .ico file: " + icoPath);
        }

        var lang = ReEntries.DEFAULT_LANG;
        var groupName = ResId.of(1);
        var first = ReCallbacks.firstName(module, ResId.of(WinApi.RT_GROUP_ICON));

        // Retire the current main icon: its group and every image it points at
        if (first != null) {
            groupName = first;
            for (var glang : ReCallbacks.getLangs(module, ResId.of(WinApi.RT_GROUP_ICON), groupName)) {
                var old = ReEntries.find(module, ResId.of(WinApi.RT_GROUP_ICON), groupName, glang);
                if (old != null && old.length >= 6) {
                    var oldCount = Le.u16(old, 4);
                    for (var k = 0; k < oldCount && 6 + (k + 1) * GROUP_ENTRY_SIZE <= old.length; k++) {
                        var imgId = Le.u16(old, 6 + k * GROUP_ENTRY_SIZE + 12);
                        if (!list.queueReplace(module, ResId.of(WinApi.RT_ICON), ResId.of(imgId))) {
                            throw new ReError("Too many resource changes in one run");
                        }
                    }
                }
            }
            if (!list.queueReplace(module, ResId.of(WinApi.RT_GROUP_ICON), groupName)) {
                throw new ReError("Too many resource changes in one run");
            }
            lang = list.lastLang;
        }

        // New image ids above every id in the file, so nothing collides
        var maxId = ReCallbacks.maxNumericName(module, ResId.of(WinApi.RT_ICON));
        if (maxId + count > 65535) {
            throw new ReError("No free icon resource ids left in the target");
        }

        var group = new ReBuf();
        group.put(java.util.Arrays.copyOf(ico, 6)); // reserved, type=1, count - copied from the .ico file verbatim

        for (var i = 0; i < count; i++) {
            var fe = 6 + i * FILE_ENTRY_SIZE;
            var width = ico[fe];
            var height = ico[fe + 1];
            var colors = ico[fe + 2];
            var planes = Le.u16(ico, fe + 4);
            var bitCount = Le.u16(ico, fe + 6);
            var bytesU = Le.i32(ico, fe + 8) & 0xFFFFFFFFL;
            var offsetU = Le.i32(ico, fe + 12) & 0xFFFFFFFFL;
            if (offsetU > icoSize || bytesU > icoSize - offsetU || bytesU == 0) {
                throw new ReError("Icon file is truncated or corrupt: " + icoPath);
            }
            var bytes = (int) bytesU;
            var offset = (int) offsetU;

            var image = java.util.Arrays.copyOfRange(ico, offset, offset + bytes);
            var imgId = maxId + 1 + i;
            if (!list.add(ResId.of(WinApi.RT_ICON), ResId.of(imgId), lang, image)) {
                throw new ReError("Too many resource changes in one run");
            }

            group.putByte(width);
            group.putByte(height);
            group.putByte(colors);
            group.putByte(0);
            group.putShort(planes);
            group.putShort(bitCount);
            group.putInt(bytes);
            group.putShort(imgId);
        }

        if (!list.add(ResId.of(WinApi.RT_GROUP_ICON), groupName, lang, group.bytes())) {
            throw new ReError("Too many resource changes in one run");
        }

        report.append("Icon: ").append(icoPath).append(" (").append(count).append(count == 1 ? " image)\n" : " images)\n");
    }
}
