package jarrunner.jr;

import org.teavm.interop.Address;

/** (ii) Any other resource, raw from a file - mirrors resedit.c's reQueueRaw. */
public final class ReRawResource {
    private ReRawResource() {}

    public static void queue(Address module, ReEntries list, ReStamp.RawResource raw, StringBuilder report) {
        var type = ResId.parse(raw.type(), true);
        var name = ResId.parse(raw.name(), false);
        checkJrcJson(raw, report);
        var file = FileIo.readAllNative(raw.file());
        if (file == null) {
            throw new ReError("Cannot read resource file: " + raw.file());
        }
        if (!list.queueReplace(module, type, name)) {
            throw new ReError("Too many resource changes in one run");
        }
        var lang = list.lastLang;
        if (!list.add(type, name, lang, file.data(), file.size())) {
            throw new ReError("Too many resource changes in one run");
        }
        report.append("Resource ").append(raw.type()).append("/").append(raw.name()).append(": ")
                .append(raw.file()).append(" (").append(file.size()).append(" bytes)\n");
    }

    /** RCDATA/JRC is jr's own config: a jrc-json is sanity-checked before it is baked, and a
     *  broken one is refused (PRP-30). A key=value .jrc is baked as before. */
    private static void checkJrcJson(ReStamp.RawResource raw, StringBuilder report) {
        if (!AsciiStr.equalsIgnoreCase(raw.type(), "RCDATA") || !AsciiStr.equalsIgnoreCase(raw.name(), "JRC")) {
            return;
        }
        var text = FileIo.readAll(raw.file());
        if (text == null || !JrcJson.looksLikeJson(text)) {
            return;
        }
        var result = JrcCheck.check(text);
        if (JrcCheck.hasErrors(result)) {
            throw new ReError("Not baking " + raw.file() + ", the config has problems:\n" + result);
        }
        report.append("Config check: ").append(raw.file()).append(result.isEmpty() ? " ok\n" : "\n" + result);
    }
}
