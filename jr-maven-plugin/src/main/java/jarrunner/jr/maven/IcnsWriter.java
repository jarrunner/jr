package jarrunner.jr.maven;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Makes a macOS .icns from the icon the pom names (PRP-36), so one icon serves the Windows exe and the Mac app.
 *  Accepts a .icns (used as it is), a .png, or a .ico whose entries are PNG (the form of jr's own icon/jr-icon.ico; a
 *  BMP-only .ico is refused with a message). The largest image is scaled down to each size macOS uses, never up,
 *  and stored as PNG entries: icp4 16, icp5 32, icp6 64, ic07 128, ic08 256, ic09 512, ic10 1024. */
final class IcnsWriter {
    private static final int[] SIZES = {16, 32, 64, 128, 256, 512, 1024};
    private static final String[] TYPES = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};

    private IcnsWriter() {}

    static byte[] from(File icon) throws IOException {
        var bytes = Files.readAllBytes(icon.toPath());
        var name = icon.getName().toLowerCase();
        if (name.endsWith(".icns")) return bytes;
        var source = name.endsWith(".ico") ? largestIcoPng(bytes, icon) : bytes;
        var image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException(icon + " is not an image Java can read (use a .png, .ico or .icns)");
        var max = Math.min(image.getWidth(), image.getHeight());
        if (max < 16) throw new IOException(icon + " is smaller than 16x16");
        var out = new ByteArrayOutputStream();
        out.write(new byte[8]); // 'icns' + total length, filled in below
        for (var i = 0; i < SIZES.length && SIZES[i] <= max; i++) {
            var png = png(scale(image, SIZES[i]));
            out.write(TYPES[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(8 + png.length).array());
            out.write(png);
        }
        var icns = out.toByteArray();
        ByteBuffer.wrap(icns).order(ByteOrder.BIG_ENDIAN).put("icns".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(icns.length);
        return icns;
    }

    /** The PNG data of the .ico's largest PNG entry. */
    private static byte[] largestIcoPng(byte[] ico, File icon) throws IOException {
        var b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        var count = b.getShort(4) & 0xFFFF;
        byte[] best = null;
        var bestSize = -1;
        for (var i = 0; i < count; i++) {
            var e = 6 + i * 16;
            var w = ico[e] & 0xFF;
            var size = w == 0 ? 256 : w;
            var len = b.getInt(e + 8);
            var off = b.getInt(e + 12);
            var isPng = len > 8 && (ico[off] & 0xFF) == 0x89 && ico[off + 1] == 'P' && ico[off + 2] == 'N' && ico[off + 3] == 'G';
            if (isPng && size > bestSize) {
                best = java.util.Arrays.copyOfRange(ico, off, off + len);
                bestSize = size;
            }
        }
        if (best == null) throw new IOException(icon + " has no PNG entry; give macosIcon a .png or .icns instead");
        return best;
    }

    /** Halves step by step down to the target: a single big bicubic step loses detail in a small icon. */
    private static BufferedImage scale(BufferedImage src, int size) {
        var img = src;
        var w = img.getWidth();
        while (w > size) {
            w = Math.max(size, w / 2);
            var next = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            var g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, w, w, null);
            g.dispose();
            img = next;
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
