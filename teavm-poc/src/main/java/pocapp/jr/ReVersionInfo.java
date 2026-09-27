package pocapp.jr;

import org.teavm.interop.Address;

import java.util.ArrayList;
import java.util.List;

import static pocapp.jr.N.*;

/**
 * (ii) Version information. The VS_VERSIONINFO block is rebuilt: the standard strings and fixed
 * versions already in the target are carried over, then the requested ones are applied on top (a
 * non-standard string name already in the target is not carried over - VerQueryValue can only look
 * names up, not list them). Mirrors resedit.c's reQueueVersion.
 */
public final class ReVersionInfo {
    private ReVersionInfo() {}

    private static final String[] STD_STRINGS = {
        "Comments", "CompanyName", "FileDescription", "FileVersion", "InternalName", "LegalCopyright",
        "LegalTrademarks", "OriginalFilename", "PrivateBuild", "ProductName", "ProductVersion", "SpecialBuild",
    };

    /** Ordered, case-insensitive-by-name upsert - mirrors reVSet (last value for a name wins, first
     *  occurrence keeps its position). */
    private static final class VStrings {
        final List<String> names = new ArrayList<>();
        final List<String> values = new ArrayList<>();

        void set(String name, String value) {
            for (var i = 0; i < names.size(); i++) {
                if (AsciiStr.equalsIgnoreCase(names.get(i), name)) {
                    values.set(i, value);
                    return;
                }
            }
            names.add(name);
            values.add(value);
        }
    }

    public static void queue(Address module, ReEntries list, ReStamp s, StringBuilder report) {
        var vs = new VStrings();
        var ffi = alloc(WinOffsets.VS_FIXEDFILEINFO.SIZE);
        WinOffsets.VS_FIXEDFILEINFO.dwSignature(ffi, 0xFEEF04BD);
        WinOffsets.VS_FIXEDFILEINFO.dwStrucVersion(ffi, 0x00010000);
        WinOffsets.VS_FIXEDFILEINFO.dwFileFlagsMask(ffi, 0x3F); // VS_FFI_FILEFLAGSMASK
        WinOffsets.VS_FIXEDFILEINFO.dwFileOS(ffi, 0x00040004); // VOS_NT_WINDOWS32
        WinOffsets.VS_FIXEDFILEINFO.dwFileType(ffi, 0x00000001); // VFT_APP

        short[] translation = {ReEntries.DEFAULT_LANG, 1200}; // en-US, Unicode

        if (!list.queueReplace(module, ResId.of(WinApi.RT_VERSION), ResId.of(WinApi.VS_VERSION_INFO))) {
            throw new ReError("Too many resource changes in one run");
        }
        var lang = list.lastLang;

        // Carry over what the target already has
        var existing = ReEntries.find(module, ResId.of(WinApi.RT_VERSION), ResId.of(WinApi.VS_VERSION_INFO), lang);
        if (existing != null && existing.size() > 0) {
            var block = existing.data();
            var value = queryValue(block, "\\");
            if (value != null && value.length() >= WinOffsets.VS_FIXEDFILEINFO.SIZE) {
                for (var i = 0; i < WinOffsets.VS_FIXEDFILEINFO.SIZE; i++) {
                    ffi.add(i).putByte(value.address().add(i).getByte());
                }
            }
            var trans = queryValue(block, "\\VarFileInfo\\Translation");
            if (trans != null && trans.length() >= 4) {
                translation[0] = trans.address().getShort();
                translation[1] = trans.address().add(2).getShort();
            }
            for (var stdName : STD_STRINGS) {
                var path = "\\StringFileInfo\\" + hex4Lower(translation[0]) + hex4Lower(translation[1]) + "\\" + stdName;
                var v = queryValue(block, path);
                if (v != null && v.length() > 0) {
                    vs.set(stdName, wstring(v.address(), v.length()));
                }
            }
        }

        // Apply the requested changes
        if (!s.fileVersion.isEmpty()) {
            var v = ReVersionNumber.parse(s.fileVersion);
            if (v == null) {
                throw new ReError("Not a version number (a.b.c.d): " + s.fileVersion);
            }
            WinOffsets.VS_FIXEDFILEINFO.dwFileVersionMS(ffi, v[0]);
            WinOffsets.VS_FIXEDFILEINFO.dwFileVersionLS(ffi, v[1]);
            vs.set("FileVersion", s.fileVersion);
        }
        if (!s.productVersion.isEmpty()) {
            var v = ReVersionNumber.parse(s.productVersion);
            if (v == null) {
                throw new ReError("Not a version number (a.b.c.d): " + s.productVersion);
            }
            WinOffsets.VS_FIXEDFILEINFO.dwProductVersionMS(ffi, v[0]);
            WinOffsets.VS_FIXEDFILEINFO.dwProductVersionLS(ffi, v[1]);
            vs.set("ProductVersion", s.productVersion);
        }
        for (var vstr : s.versionStrings) {
            vs.set(vstr.name(), vstr.value());
        }

        // Build the block
        var tableKey = hex4Upper(translation[0]) + hex4Upper(translation[1]);
        var b = new ReBuf();
        var root = b.begin("VS_VERSION_INFO", WinOffsets.VS_FIXEDFILEINFO.SIZE, 0);
        b.putStruct(ffi, WinOffsets.VS_FIXEDFILEINFO.SIZE);
        var sfi = b.begin("StringFileInfo", 0, 1);
        var table = b.begin(tableKey, 0, 1);
        for (var i = 0; i < vs.names.size(); i++) {
            var node = b.begin(vs.names.get(i), vs.values.get(i).length() + 1, 1);
            b.putWideStringZ(vs.values.get(i));
            b.end(node);
        }
        b.end(table);
        b.end(sfi);
        var vfi = b.begin("VarFileInfo", 0, 1);
        var var_ = b.begin("Translation", 2, 0);
        b.putShort(translation[0]);
        b.putShort(translation[1]);
        b.end(var_);
        b.end(vfi);
        b.end(root);

        if (b.len > 65535) {
            throw new ReError("Version information too large");
        }

        if (!list.add(ResId.of(WinApi.RT_VERSION), ResId.of(WinApi.VS_VERSION_INFO), lang, b.base, b.len)) {
            throw new ReError("Too many resource changes in one run");
        }

        report.append("Version information:");
        if (!s.fileVersion.isEmpty()) {
            report.append(" file ").append(s.fileVersion);
        }
        if (!s.productVersion.isEmpty() && !s.productVersion.equals(s.fileVersion)) {
            report.append(" product ").append(s.productVersion);
        }
        for (var i = 0; i < s.versionStrings.size(); i++) {
            var vstr = s.versionStrings.get(i);
            if (i > 0 || !s.fileVersion.isEmpty() || !s.productVersion.isEmpty()) {
                report.append(",");
            }
            report.append(" ").append(vstr.name()).append("=\"").append(vstr.value()).append("\"");
        }
        report.append("\n");
    }

    private record QueryResult(Address address, int length) {}

    private static QueryResult queryValue(Address block, String path) {
        var outPtr = ptrVar();
        var outLen = intVar();
        if (WinApi.verQueryValueW(block, wcstr(path), outPtr, outLen) == 0) {
            return null;
        }
        return new QueryResult(outPtr.getAddress(), outLen.getInt());
    }

    private static String hex4Lower(short v) {
        return hex4(v, "0123456789abcdef");
    }

    private static String hex4Upper(short v) {
        return hex4(v, "0123456789ABCDEF");
    }

    private static String hex4(short v, String digits) {
        var n = v & 0xFFFF;
        var chars = new char[4];
        for (var i = 3; i >= 0; i--) {
            chars[i] = digits.charAt(n & 0xF);
            n >>>= 4;
        }
        return new String(chars);
    }
}
