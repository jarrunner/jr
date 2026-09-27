package pocapp.jr;

import static pocapp.jr.N.*;

/** Finding java on PATH (or --java-home) - POSIX twin of the Windows JavaFinder: ':' path
 *  separator, '/' directory separator, realpath(3) in place of GetFinalPathNameByHandleA. No
 *  jli.dll equivalent - there is no in-process JVM-load mode on this build (Windows-only, see
 *  ConsoleMode's note on scope). */
public final class JavaFinder {
    private JavaFinder() {}

    public static String findInPath(String exeName) {
        var path = Cstr.readEnv("PATH");
        if (path == null) {
            return null;
        }
        var start = 0;
        while (start <= path.length()) {
            var sep = path.indexOf(':', start);
            var dir = sep < 0 ? path.substring(start) : path.substring(start, sep);
            if (!dir.isEmpty()) {
                var candidate = dir + "/" + exeName;
                if (FileIo.exists(candidate)) {
                    return candidate;
                }
            }
            if (sep < 0) {
                break;
            }
            start = sep + 1;
        }
        return null;
    }

    /** Major version of a java binary, read from &lt;home&gt;/release rather than by spawning
     *  "java -version". Follows a symlink (realpath) when the direct parent has no release file -
     *  mirrors launcher.c's detectJavaMajor and the Windows JavaFinder. */
    public static int detectMajorVersion(String javaExePath) {
        var home = Paths.dirOf(Paths.dirOf(javaExePath));
        var major = home.isEmpty() ? 0 : readReleaseMajor(home);

        if (major == 0) {
            var realPath = resolveRealPath(javaExePath);
            if (realPath != null) {
                home = Paths.dirOf(Paths.dirOf(realPath));
                if (!home.isEmpty()) {
                    major = readReleaseMajor(home);
                }
            }
        }
        return major;
    }

    private static int readReleaseMajor(String jdkHome) {
        var text = FileIo.readAll(jdkHome + "/release");
        if (text == null) {
            return 0;
        }
        for (var line : Lines.split(text)) {
            if (line.startsWith("JAVA_VERSION=")) {
                var v = line.substring("JAVA_VERSION=".length());
                if (v.startsWith("\"")) {
                    v = v.substring(1);
                }
                var major = Atoi.parse(v);
                if (major == 1) { // legacy 1.x numbering
                    var dot = v.indexOf('.');
                    major = dot >= 0 ? Atoi.parse(v.substring(dot + 1)) : 0;
                }
                return major;
            }
        }
        return 0;
    }

    private static String resolveRealPath(String path) {
        var buf = alloc(PosixApi.PATH_MAX);
        var p = PosixApi.realpath(cstr(path), buf);
        return p.toLong() == 0 ? null : string(buf);
    }
}
