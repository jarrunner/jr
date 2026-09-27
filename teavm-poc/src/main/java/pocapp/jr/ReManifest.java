package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/**
 * (ii) Manifest and requested execution level. The level is changed inside whichever manifest
 * applies (a given file, else the target's own), and a trustInfo block is added if that manifest
 * has none; with neither, a minimal manifest is generated. Mirrors resedit.c's reQueueManifest.
 *
 * Manifest text is handled as a plain byte-per-char Java String, the same way resedit.c treats it
 * as an opaque char* rather than parsed XML - a strstr-equivalent substring search, not a DOM.
 */
public final class ReManifest {
    private ReManifest() {}

    private static final String TRUST_INFO_HEAD =
            "  <trustInfo xmlns=\"urn:schemas-microsoft-com:asm.v3\">\r\n"
            + "    <security>\r\n"
            + "      <requestedPrivileges>\r\n"
            + "        <requestedExecutionLevel level=\"";
    private static final String TRUST_INFO_TAIL =
            "\" uiAccess=\"false\"/>\r\n"
            + "      </requestedPrivileges>\r\n"
            + "    </security>\r\n"
            + "  </trustInfo>\r\n";

    private static final String GENERATED_MANIFEST =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n"
            + "<assembly xmlns=\"urn:schemas-microsoft-com:asm.v1\" manifestVersion=\"1.0\">\r\n"
            + "</assembly>\r\n";

    public static void queue(Address module, ReEntries list, ReStamp s, StringBuilder report) {
        if (!list.queueReplace(module, ResId.of(WinApi.RT_MANIFEST), ResId.of(1))) {
            throw new ReError("Too many resource changes in one run");
        }
        var lang = list.lastLang;

        String base;
        String source;
        if (!s.manifest.isEmpty()) {
            base = FileIo.readAll(s.manifest);
            if (base == null) {
                throw new ReError("Cannot read manifest file: " + s.manifest);
            }
            source = s.manifest;
        } else {
            var existing = ReEntries.find(module, ResId.of(WinApi.RT_MANIFEST), ResId.of(1), lang);
            if (existing != null && existing.size() > 0) {
                base = rawBytesToString(existing.data(), existing.size());
                source = "the existing manifest";
            } else {
                base = GENERATED_MANIFEST;
                source = "a generated manifest";
            }
        }

        String out;
        if (s.executionLevel.isEmpty()) {
            out = base;
        } else {
            var rel = base.indexOf("requestedExecutionLevel");
            var level = rel >= 0 ? base.indexOf("level=\"", rel) : -1;
            var tagEnd = rel >= 0 ? base.indexOf('>', rel) : -1;
            if (level >= 0 && (tagEnd < 0 || level < tagEnd)) {
                var valueStart = level + 7; // after level="
                var valueEnd = base.indexOf('"', valueStart);
                if (valueEnd < 0) {
                    throw new ReError("Malformed requestedExecutionLevel in " + source);
                }
                out = base.substring(0, valueStart) + s.executionLevel + base.substring(valueEnd);
            } else {
                var close = base.indexOf("</assembly>");
                if (close < 0) {
                    throw new ReError("No </assembly> in " + source);
                }
                out = base.substring(0, close) + TRUST_INFO_HEAD + s.executionLevel + TRUST_INFO_TAIL
                        + base.substring(close);
            }
        }

        var outBytes = alloc(out.length());
        for (var i = 0; i < out.length(); i++) {
            outBytes.add(i).putByte((byte) out.charAt(i));
        }
        if (!list.add(ResId.of(WinApi.RT_MANIFEST), ResId.of(1), lang, outBytes, out.length())) {
            throw new ReError("Too many resource changes in one run");
        }

        if (!s.manifest.isEmpty()) {
            report.append("Manifest: ").append(s.manifest).append("\n");
        }
        if (!s.executionLevel.isEmpty()) {
            report.append("Execution level: ").append(s.executionLevel).append("\n");
        }
    }

    private static String rawBytesToString(Address p, int len) {
        var chars = new char[len];
        for (var i = 0; i < len; i++) {
            chars[i] = (char) (p.add(i).getByte() & 0xFF);
        }
        return new String(chars);
    }
}
