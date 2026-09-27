package pocapp.jr;

import org.teavm.interop.Address;

/** (ii) Any other resource, raw from a file - mirrors resedit.c's reQueueRaw. */
public final class ReRawResource {
    private ReRawResource() {}

    public static void queue(Address module, ReEntries list, ReStamp.RawResource raw, StringBuilder report) {
        var type = ResId.parse(raw.type(), true);
        var name = ResId.parse(raw.name(), false);
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
}
