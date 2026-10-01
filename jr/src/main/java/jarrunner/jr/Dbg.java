package jarrunner.jr;

import static jarrunner.jr.N.*;

/** Developer tracing, independent of any .jrc: with JR_DEBUG=1 set, appends one line per call to
 *  %TEMP%\jr-debug.log. Log.java can't cover this - its file path comes from the .jrc, so it is
 *  silent for anything that runs before (or goes wrong while) the config loads. Safe to leave
 *  calls in shipped code: without JR_DEBUG it costs one cached env lookup. */
public final class Dbg {
    private Dbg() {}

    private static int enabled = -1; // -1 unknown, 0 off, 1 on
    private static String path;

    public static void log(String msg) {
        if (enabled == -1) {
            enabled = Cstr.readEnv("JR_DEBUG") != null ? 1 : 0;
            if (enabled == 1) {
                var temp = Cstr.readEnv("TEMP");
                path = (temp != null && !temp.isEmpty() ? temp : "C:\\Windows\\Temp") + "\\jr-debug.log";
            }
        }
        if (enabled == 0) {
            return;
        }
        var f = WinApi.fopen(cstr(path), cstr("a"));
        if (f.toLong() == 0) {
            return;
        }
        FileIo.append(f, "[" + ExeInfo.baseNameNoExt() + "] " + msg + "\n");
        WinApi.fclose(f);
    }
}
