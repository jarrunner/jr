package jarrunner.jr.maven;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Writes an app's config into a macOS jr binary and keeps its ad-hoc signature valid (PRP-36), in plain Java so
 *  it runs on Windows and in CI as well as on a Mac.
 *
 *  jr's macOS build links an empty 16 KB {@code __DATA,__jrc} section into every binary (build-macos.sh). The config
 *  is written there as UTF-8 plus a NUL, in place, so no offset, size or load command changes. What does change is
 *  the hash of each 4 KB page holding it, which the binary's CodeDirectory records: arm64 macOS refuses to run code
 *  whose pages no longer match. So the matching hash slots are recomputed, in every CodeDirectory of every slice of
 *  a universal binary. An ad-hoc signature has no CMS blob over the CodeDirectory, so nothing else needs
 *  re-signing. This is the whole of what the plugin needs from a code signer, not a general one. */
public final class MachOStamper {
    static final String SEGMENT = "__DATA";
    static final String SECTION = "__jrc";

    private static final int FAT_MAGIC = 0xCAFEBABE;
    private static final int MH_MAGIC_64 = 0xFEEDFACF;
    private static final int LC_SEGMENT_64 = 0x19;
    private static final int LC_CODE_SIGNATURE = 0x1D;
    private static final int CSMAGIC_EMBEDDED_SIGNATURE = 0xFADE0CC0;
    private static final int CSMAGIC_CODEDIRECTORY = 0xFADE0C02;

    private MachOStamper() {}

    /** Returns the binary's bytes with the config written into every slice and the signatures updated. */
    public static byte[] stamp(byte[] binary, String config) throws IOException {
        var text = config.getBytes(StandardCharsets.UTF_8);
        var out = binary.clone();
        var slices = slices(out);
        for (var s : slices) {
            stampSlice(out, s[0], s[1], text);
        }
        return out;
    }

    /** The config currently in the binary's first slice, or "" (for tests and for checking a build). */
    public static String read(byte[] binary) throws IOException {
        var s = slices(binary).get(0);
        var sect = section(binary, s[0]);
        var start = s[0] + sect[0];
        var end = start;
        while (end < start + sect[1] && binary[end] != 0) end++;
        return new String(binary, start, end - start, StandardCharsets.UTF_8);
    }

    /** {offset, size} of each thin Mach-O inside the file: the whole file, or each architecture of a fat file. */
    static List<int[]> slices(byte[] b) throws IOException {
        var be = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        var list = new ArrayList<int[]>();
        if (be.getInt(0) == FAT_MAGIC) {
            var n = be.getInt(4);
            for (var i = 0; i < n; i++) {
                var at = 8 + i * 20;
                list.add(new int[] {be.getInt(at + 8), be.getInt(at + 12)});
            }
        } else {
            list.add(new int[] {0, b.length});
        }
        for (var s : list) {
            if (le(b).getInt(s[0]) != MH_MAGIC_64) throw new IOException("not a 64-bit Mach-O binary");
        }
        return list;
    }

    private static void stampSlice(byte[] b, int base, int size, byte[] text) throws IOException {
        var sect = section(b, base);
        if (text.length + 1 > sect[1]) {
            throw new IOException("the config is " + text.length + " bytes; jr's " + SEGMENT + "," + SECTION
                    + " section holds " + (sect[1] - 1));
        }
        var start = base + sect[0];
        java.util.Arrays.fill(b, start, start + sect[1], (byte) 0);
        System.arraycopy(text, 0, b, start, text.length);
        resign(b, base, size, sect[0], sect[1]);
    }

    /** {file offset relative to the slice, size} of __DATA,__jrc. */
    private static int[] section(byte[] b, int base) throws IOException {
        var h = le(b);
        var ncmds = h.getInt(base + 16);
        var at = base + 32;
        for (var i = 0; i < ncmds; i++) {
            var cmd = h.getInt(at);
            var cmdsize = h.getInt(at + 4);
            if (cmd == LC_SEGMENT_64 && name(b, at + 8).equals(SEGMENT)) {
                var nsects = h.getInt(at + 64);
                for (var j = 0; j < nsects; j++) {
                    var s = at + 72 + j * 80;
                    if (name(b, s).equals(SECTION)) {
                        return new int[] {h.getInt(s + 48), (int) h.getLong(s + 40)};
                    }
                }
            }
            at += cmdsize;
        }
        throw new IOException("this jr binary has no " + SEGMENT + "," + SECTION
                + " section; it was built before PRP-36 and cannot carry a config");
    }

    /** Recomputes the code-page hashes covering [from, from+len) in every CodeDirectory of the slice. */
    private static void resign(byte[] b, int base, int size, int from, int len) throws IOException {
        var h = le(b);
        var ncmds = h.getInt(base + 16);
        var at = base + 32;
        int sigOff = -1;
        for (var i = 0; i < ncmds; i++) {
            if (h.getInt(at) == LC_CODE_SIGNATURE) sigOff = h.getInt(at + 8);
            at += h.getInt(at + 4);
        }
        if (sigOff < 0) throw new IOException("the jr binary is not signed; arm64 macOS would refuse to run it");
        var be = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);
        var sb = base + sigOff;
        if (be.getInt(sb) != CSMAGIC_EMBEDDED_SIGNATURE) throw new IOException("unrecognised code signature");
        var count = be.getInt(sb + 8);
        var directories = 0;
        for (var i = 0; i < count; i++) {
            var cd = sb + be.getInt(sb + 12 + i * 8 + 4);
            if (be.getInt(cd) != CSMAGIC_CODEDIRECTORY) continue;
            directories++;
            var hashOffset = be.getInt(cd + 16);
            var nCodeSlots = be.getInt(cd + 28);
            var codeLimit = be.getInt(cd + 32);
            var hashSize = b[cd + 36] & 0xFF;
            var hashType = b[cd + 37] & 0xFF;
            var pageSize = 1 << (b[cd + 39] & 0xFF);
            var md = digest(hashType);
            for (var page = from / pageSize; page <= (from + len - 1) / pageSize && page < nCodeSlots; page++) {
                var start = page * pageSize;
                var end = Math.min(start + pageSize, codeLimit);
                md.reset();
                md.update(b, base + start, end - start);
                System.arraycopy(md.digest(), 0, b, cd + hashOffset + page * hashSize, hashSize);
            }
        }
        if (directories == 0) throw new IOException("the code signature has no CodeDirectory");
    }

    private static MessageDigest digest(int hashType) throws IOException {
        try {
            return MessageDigest.getInstance(switch (hashType) {
                case 1 -> "SHA-1";
                case 2, 3 -> "SHA-256"; // 3: SHA-256 truncated to hashSize bytes
                case 4 -> "SHA-384";
                default -> throw new IOException("unknown code signature hash type " + hashType);
            });
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String name(byte[] b, int at) {
        var end = at;
        while (end < at + 16 && b[end] != 0) end++;
        return new String(b, at, end - at, StandardCharsets.US_ASCII);
    }

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** For trying it by hand: java MachOStamper.java &lt;jr-binary&gt; &lt;config-file&gt; &lt;out&gt; */
    public static void main(String[] args) throws Exception {
        var out = stamp(Files.readAllBytes(Path.of(args[0])), Files.readString(Path.of(args[1])));
        Files.write(Path.of(args[2]), out);
        System.out.println("wrote " + args[2] + " (" + slices(out).size() + " slice(s))");
    }
}
