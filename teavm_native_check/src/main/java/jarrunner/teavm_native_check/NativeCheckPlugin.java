package jarrunner.teavm_native_check;

import com.sun.source.util.*;

/** {@code javac -Xplugin:"NativeCheck scope=pkg.N.memScoped raw=pkg.N,pkg.Arena mode=error"}. See {@link Config}. */
public final class NativeCheckPlugin implements Plugin {
    @Override public String getName() { return "NativeCheck"; }

    @Override public void init(JavacTask task, String... args) {
        task.addTaskListener(new AnalyzeListener(task, Config.parse(args)));
    }
}
