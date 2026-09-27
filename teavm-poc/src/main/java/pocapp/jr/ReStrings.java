package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/**
 * (ii) String table. Strings live in blocks of 16 (block = id/16 + 1), each entry a length-prefixed
 * UTF-16 string with NO NUL terminator, so setting one string means rewriting its whole block with
 * the other fifteen carried over. Mirrors resedit.c's reQueueStrings.
 */
public final class ReStrings {
    private ReStrings() {}

    public static void queue(Address module, ReEntries list, ReStamp s, StringBuilder report) {
        var done = new boolean[s.strings.size()];

        for (var i = 0; i < s.strings.size(); i++) {
            if (done[i]) {
                continue;
            }
            var block = s.strings.get(i).id() / 16 + 1;
            var texts = new String[16];
            java.util.Arrays.fill(texts, "");

            if (!list.queueReplace(module, ResId.of(WinApi.RT_STRING), ResId.of(block))) {
                throw new ReError("Too many resource changes in one run");
            }
            var lang = list.lastLang;

            // Carry over the block's other strings
            var old = ReEntries.find(module, ResId.of(WinApi.RT_STRING), ResId.of(block), lang);
            if (old != null) {
                var q = 0;
                for (var j = 0; j < 16 && q + 2 <= old.size(); j++) {
                    var len = old.data().add(q).getShort() & 0xFFFF;
                    q += 2;
                    if (q + len * 2 > old.size()) {
                        break;
                    }
                    var chars = new char[len];
                    for (var c = 0; c < len; c++) {
                        chars[c] = old.data().add(q + c * 2).getChar();
                    }
                    texts[j] = new String(chars);
                    q += len * 2;
                }
            }

            // This block's requested strings (the last one given wins)
            for (var j = i; j < s.strings.size(); j++) {
                var entry = s.strings.get(j);
                if (entry.id() / 16 + 1 == block) {
                    texts[entry.id() % 16] = entry.text();
                    done[j] = true;
                }
            }

            var outSize = 0;
            for (var t : texts) {
                outSize += 2 + t.length() * 2;
            }
            var out = alloc(outSize);
            var p = 0;
            for (var t : texts) {
                out.add(p).putShort((short) t.length());
                for (var c = 0; c < t.length(); c++) {
                    out.add(p + 2 + c * 2).putShort((short) t.charAt(c));
                }
                p += 2 + t.length() * 2;
            }
            if (!list.add(ResId.of(WinApi.RT_STRING), ResId.of(block), lang, out, outSize)) {
                throw new ReError("Too many resource changes in one run");
            }
        }

        for (var entry : s.strings) {
            report.append("String ").append(entry.id()).append(": \"").append(entry.text()).append("\"\n");
        }
    }
}
