package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

/** Hand-rolled line splitter, shared by JavaFinder (JDK release-file
 *  parsing) - avoids String.split() pulling java.util.regex.Pattern into the TeaVM build (a real
 *  size contributor - see 11-prp.01.size-experiments.md) just for a literal "\n" separator. */
public final class Lines {
    private Lines() {}

    public static List<String> split(String text) {
        var out = new ArrayList<String>();
        var start = 0;
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i));
                start = i + 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }
}
