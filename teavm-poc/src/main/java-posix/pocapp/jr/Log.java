package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/** Opt-in logging to a file - POSIX twin of the Windows Log, PosixApi.fopen/fclose in place of
 *  WinApi's (FileIo.append already dispatches through PosixApi). */
public final class Log {
    private Log() {}

    private static Address file;
    private static boolean enabled;

    public static void init(String path, boolean overwrite) {
        if (path == null || path.isEmpty()) {
            enabled = false;
            return;
        }
        file = PosixApi.fopen(cstr(path), cstr(overwrite ? "w" : "a"));
        if (file.toLong() != 0) {
            enabled = true;
            FileIo.append(file, "\n========================================\n");
            FileIo.append(file, "Java Runner Log - " + Epoch.formatNow() + "\n");
            FileIo.append(file, "========================================\n");
        }
    }

    public static void info(String msg) {
        write("INFO", msg);
    }

    public static void warn(String msg) {
        write("WARNING", msg);
    }

    public static void error(String msg) {
        write("ERROR", msg);
    }

    static void write(String level, String msg) {
        if (!enabled) {
            return;
        }
        FileIo.append(file, "[" + level + "] " + msg + "\n");
    }

    public static void close() {
        if (enabled) {
            FileIo.append(file, "========================================\n\n");
            PosixApi.fclose(file);
            enabled = false;
        }
    }
}
