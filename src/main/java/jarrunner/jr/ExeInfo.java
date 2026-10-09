package jarrunner.jr;

import static jarrunner.jr.N.*;

/** This executable's own path/name via GetModuleFileNameW, for display, naming and reading its own
 *  resources, mirroring launcher.c's getExeBaseName. */
public final class ExeInfo {
    private ExeInfo() {}

    public static String baseNameNoExt() {
        return stripExeExt(Paths.fileNameOf(fullPath()));
    }

    /** The full path, WITH its .exe extension. */
    public static String fullPath() {
        if (fullPath == null) {
            fullPath = memScoped(() -> {
                var buf = alloc(4096 * 2);
                return wstring(buf, WinApi.getModuleFileNameW(NULL, buf, 4096));
            });
        }
        return fullPath;
    }

    private static String fullPath;

    private static String stripExeExt(String path) {
        return AsciiStr.endsWithIgnoreCase(path, ".exe") ? path.substring(0, path.length() - 4) : path;
    }
}
