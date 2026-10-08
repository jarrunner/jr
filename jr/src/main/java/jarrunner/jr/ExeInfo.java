package jarrunner.jr;

import static jarrunner.jr.N.*;

/** This executable's own path/name via GetModuleFileNameW - used to find the sibling .jrc file
 *  and for display purposes, mirroring launcher.c's getExeBaseName/getExeFullPathWithoutExt. */
public final class ExeInfo {
    private ExeInfo() {}

    public static String fullPathNoExt() {
        return stripExeExt(fullPath());
    }

    public static String baseNameNoExt() {
        return stripExeExt(Paths.fileNameOf(fullPath()));
    }

    /** The full path, WITH its .exe extension - resedit.c's reRun (-Xjr:make) needs this one, unlike
     *  every other caller here which wants the .jrc-sibling form without it. */
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
