package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;
import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** PRP-31: where Java homes are looked for. A java.exe on PATH counts only through the home it belongs to:
 *  a launcher copy (Oracle's javapath), a Scoop shim or a Chocolatey shim is resolved to its real home, or
 *  recorded as rejected. Quick sources (PATH, JAVA_HOME, jr's own cache) come first; the registry and the
 *  well-known install folders are only read when the quick ones have no exact match. */
public final class JavaSources {
    private JavaSources() {}

    private static final String[] VENDOR_DIRS = {"Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto",
            "BellSoft", "Semeru", "OpenJDK"};
    private static final String[] JAVASOFT = {"JDK", "JRE", "Java Development Kit", "Java Runtime Environment"};

    static void quick(List<JavaHome> out, String exeName, String cacheRoot, boolean onlyCache) {
        if (!onlyCache) {
            path(out, exeName);
            var javaHome = Cstr.readEnv("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank()) {
                add(out, javaHome, "JAVA_HOME", exeName);
            }
        }
        if (cacheRoot != null) {
            for (var name : Dirs.listDirNames(cacheRoot)) {
                add(out, cacheRoot + "\\" + name, "jr cache", exeName);
            }
        }
    }

    static void slow(List<JavaHome> out, String exeName) {
        for (var group : JAVASOFT) {
            for (var home : registryHomes("SOFTWARE\\JavaSoft\\" + group)) {
                add(out, home, "registry", exeName);
            }
        }
        var pf = Cstr.readEnv("ProgramFiles");
        for (var vendor : VENDOR_DIRS) {
            scan(out, pf + "\\" + vendor, "Program Files", exeName);
        }
        var user = Cstr.readEnv("USERPROFILE");
        if (user != null) {
            scan(out, user + "\\.jdks", "IntelliJ .jdks", exeName);
            for (var app : Dirs.listDirNames(user + "\\scoop\\apps")) {
                var lower = AsciiStr.lower(app);
                if (lower.contains("jdk") || lower.contains("jre") || lower.contains("java")) {
                    add(out, user + "\\scoop\\apps\\" + app + "\\current", "Scoop", exeName);
                }
            }
        }
    }

    private static void scan(List<JavaHome> out, String root, String source, String exeName) {
        for (var name : Dirs.listDirNames(root)) {
            add(out, root + "\\" + name, source, exeName);
        }
    }

    private static void path(List<JavaHome> out, String exeName) {
        var path = Cstr.readEnv("PATH");
        if (path == null) {
            return;
        }
        var start = 0;
        while (start <= path.length()) {
            var sep = path.indexOf(';', start);
            var dir = JavaHome.clean(sep < 0 ? path.substring(start) : path.substring(start, sep));
            if (!dir.isEmpty() && FileIo.exists(dir + "\\" + exeName)) {
                pathEntry(out, dir, exeName);
            }
            if (sep < 0) {
                break;
            }
            start = sep + 1;
        }
    }

    /** A real bin folder gives its parent. Anything else is a launcher copy or a shim. */
    private static void pathEntry(List<JavaHome> out, String dir, String exeName) {
        var exe = dir + "\\" + exeName;
        if (AsciiStr.endsWithIgnoreCase(dir, "\\bin")
                && (FileIo.exists(dir + "\\jli.dll") || FileIo.exists(Paths.dirOf(dir) + "\\release"))) {
            add(out, Paths.dirOf(dir), "PATH", exeName);
            return;
        }
        var real = JavaFinder.resolveRealPath(exe);
        if (real != null && !AsciiStr.equalsIgnoreCase(real, exe)) {
            add(out, Paths.dirOf(Paths.dirOf(real)), "PATH (link)", exeName);
            return;
        }
        var shim = FileIo.readAll(dir + "\\" + Paths.baseNameNoExt(exeName) + ".shim");
        if (shim != null) {
            var target = shimTarget(shim);
            if (target != null) {
                add(out, Paths.dirOf(Paths.dirOf(target)), "PATH (Scoop shim)", exeName);
                return;
            }
        }
        out.add(JavaHome.rejected(exe, "PATH", AsciiStr.lower(dir).contains("javapath")
                ? "Oracle launcher copy; its home is looked for in the registry" : "a shim or launcher copy, no Java home beside it"));
    }

    /** Scoop: path = "C:\Users\x\scoop\apps\temurin21-jdk\current\bin\java.exe" */
    private static String shimTarget(String shim) {
        for (var line : Lines.split(shim)) {
            var l = line.strip();
            if (l.startsWith("path")) {
                var eq = l.indexOf('=');
                return eq < 0 ? null : JavaHome.clean(l.substring(eq + 1));
            }
        }
        return null;
    }

    private static void add(List<JavaHome> out, String home, String source, String exeName) {
        var key = JavaHome.normalize(home);
        for (var h : out) {
            if (AsciiStr.equalsIgnoreCase(h.home, key)) {
                return;
            }
        }
        out.add(JavaHome.inspect(key, source, exeName));
    }

    /** JavaHome values under HKLM\<key>\<version>, 64-bit view. */
    private static List<String> registryHomes(String key) {
        return memScoped(() -> {
            var homes = new ArrayList<String>();
            var hkey = ptrVar();
            if (WinApi.regOpenKeyExA(WinApi.HKEY_LOCAL_MACHINE, cstr(key), 0, WinApi.KEY_READ | WinApi.KEY_WOW64_64KEY, hkey)
                    != WinApi.ERROR_SUCCESS) {
                return homes;
            }
            var k = hkey.getAddress();
            var name = alloc(256);
            var nameLen = intVar();
            for (var i = 0; ; i++) {
                nameLen.putInt(256);
                if (WinApi.regEnumKeyExA(k, i, name, nameLen, NULL, NULL, NULL, NULL) != WinApi.ERROR_SUCCESS) {
                    break;
                }
                var home = registryValue(k, string(name, nameLen.getInt()), "JavaHome");
                if (home != null) {
                    homes.add(home);
                }
            }
            WinApi.regCloseKey(k);
            return homes;
        });
    }

    private static String registryValue(Address parent, String sub, String value) {
        var hkey = ptrVar();
        if (WinApi.regOpenKeyExA(parent, cstr(sub), 0, WinApi.KEY_READ | WinApi.KEY_WOW64_64KEY, hkey) != WinApi.ERROR_SUCCESS) {
            return null;
        }
        var k = hkey.getAddress();
        var buf = alloc(1024);
        var len = intVar();
        len.putInt(1023);
        var ok = WinApi.regQueryValueExA(k, cstr(value), NULL, NULL, buf, len) == WinApi.ERROR_SUCCESS;
        WinApi.regCloseKey(k);
        return ok ? string(buf, len.getInt()) : null;
    }
}
