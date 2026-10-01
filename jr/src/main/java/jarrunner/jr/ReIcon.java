package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

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
        var ico = FileIo.readAllNative(icoPath);
        if (ico == null) {
            throw new ReError("Cannot read icon file: " + icoPath);
        }
        var icoData = ico.data();
        var icoSize = ico.size();
        if (icoSize < 6) {
            throw new ReError("Not a valid .ico file: " + icoPath);
        }
        var reserved = icoData.getShort();
        var type = icoData.add(2).getShort();
        var count = icoData.add(4).getShort() & 0xFFFF;
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
                if (old != null && old.size() >= 6) {
                    var oldCount = old.data().add(4).getShort() & 0xFFFF;
                    for (var k = 0; k < oldCount && 6 + (k + 1) * GROUP_ENTRY_SIZE <= old.size(); k++) {
                        var imgId = old.data().add(6 + k * GROUP_ENTRY_SIZE + 12).getShort() & 0xFFFF;
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

        var groupSize = 6 + count * GROUP_ENTRY_SIZE;
        var group = alloc(groupSize);
        for (var i = 0; i < 6; i++) { // reserved, type=1, count - copied from the .ico file verbatim
            group.add(i).putByte(icoData.add(i).getByte());
        }

        for (var i = 0; i < count; i++) {
            var fe = icoData.add(6 + i * FILE_ENTRY_SIZE);
            var width = fe.getByte();
            var height = fe.add(1).getByte();
            var colors = fe.add(2).getByte();
            var planes = fe.add(4).getShort();
            var bitCount = fe.add(6).getShort();
            var bytesU = fe.add(8).getInt() & 0xFFFFFFFFL;
            var offsetU = fe.add(12).getInt() & 0xFFFFFFFFL;
            if (offsetU > icoSize || bytesU > icoSize - offsetU || bytesU == 0) {
                throw new ReError("Icon file is truncated or corrupt: " + icoPath);
            }
            var bytes = (int) bytesU;
            var offset = (int) offsetU;

            var image = alloc(bytes);
            for (var b = 0; b < bytes; b++) {
                image.add(b).putByte(icoData.add(offset + b).getByte());
            }
            var imgId = maxId + 1 + i;
            if (!list.add(ResId.of(WinApi.RT_ICON), ResId.of(imgId), lang, image, bytes)) {
                throw new ReError("Too many resource changes in one run");
            }

            var ge = group.add(6 + i * GROUP_ENTRY_SIZE);
            ge.putByte(width);
            ge.add(1).putByte(height);
            ge.add(2).putByte(colors);
            ge.add(3).putByte((byte) 0);
            ge.add(4).putShort(planes);
            ge.add(6).putShort(bitCount);
            ge.add(8).putInt(bytes);
            ge.add(12).putShort((short) imgId);
        }

        if (!list.add(ResId.of(WinApi.RT_GROUP_ICON), groupName, lang, group, groupSize)) {
            throw new ReError("Too many resource changes in one run");
        }

        report.append("Icon: ").append(icoPath).append(" (").append(count).append(count == 1 ? " image)\n" : " images)\n");
    }
}
