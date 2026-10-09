package jarrunner.jr;

import static jarrunner.jr.N.*;

/** -Xjr:list-resources=&lt;exe&gt; - mirrors resedit.c's reList. */
public final class ReList {
    private ReList() {}

    public static String list(String path) throws ReError {
        var m = WinApi.loadLibraryExW(path, NULL, WinApi.LOAD_LIBRARY_AS_DATAFILE | WinApi.LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        if (m.toLong() == 0) {
            throw new ReError("Cannot open " + path + " (error " + WinApi.getLastError() + ")");
        }
        var report = new StringBuilder("Resources in ").append(path).append(":\n");
        report.append(ReCallbacks.listResources(m));
        var count = ReCallbacks.listCount();
        WinApi.freeLibrary(m);
        if (count == 0) {
            report.append("  (none)\n");
        }
        return report.toString();
    }
}
