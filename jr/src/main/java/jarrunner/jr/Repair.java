package jarrunner.jr;

/** PRP-31 -Xjr:repair: fixes what the doctor finds, touching only what jr itself made: AOT caches, a
 *  downloaded jar that no longer matches its checksum, damaged JDKs in jr's download cache. Never a Java
 *  jr did not install, never an environment variable. A download asks first, as auto-install does. */
public final class Repair {
    private Repair() {}

    static String run(Config config, String exeName, boolean hasConsole, boolean guiMode) {
        var sb = new StringBuilder("jr repair: ").append(ExeInfo.fullPath()).append('\n');
        var remote = RemoteJar.localPath(config);
        var jar = remote != null ? remote : JarPath.fromArgsString(config.javaArgs);
        if (jar != null && !jar.isEmpty()) {
            var dir = Paths.dirOf(jar);
            var n = 0;
            for (var f : Dirs.matching(dir, Paths.baseNameNoExt(jar) + ".*.aot")) {
                n += WinApi.deleteFileA(N.cstr(dir + "\\" + f)) != 0 ? 1 : 0;
            }
            sb.append("- AOT caches deleted: ").append(n).append(" (the next launch makes a new one)\n");
            if (remote != null && FileIo.exists(jar)) {
                var actual = Sha256.ofFile(jar);
                if (actual != null && AsciiStr.equalsIgnoreCase(actual, config.runSha256)) {
                    RemoteJar.reverify(jar, AsciiStr.lower(config.runSha256));
                    sb.append("- Jar verified by SHA-256: ").append(jar).append('\n');
                } else {
                    WinApi.deleteFileA(N.cstr(jar));
                    WinApi.deleteFileA(N.cstr(jar + ".jr-sha256"));
                    sb.append("- Jar did not match its SHA-256 and was deleted; the next launch downloads it again: ").append(jar).append('\n');
                }
            }
        }
        java(sb, config, exeName, hasConsole, guiMode);
        return sb.toString();
    }

    /** "25" or "25-jre": a folder jr or jbang downloaded into. Never a link such as jbang's "default". */
    static boolean slotName(String name) {
        var n = name.endsWith("-jre") ? name.substring(0, name.length() - 4) : name;
        return JavaRange.major(n) > 0 && !n.startsWith("1.");
    }

    private static void java(StringBuilder sb, Config config, String exeName, boolean hasConsole, boolean guiMode) {
        var range = JavaRange.of(config);
        var cacheRoot = JavaInstall.cacheRoot();
        if (range.error != null || !config.javaHome.isEmpty() || cacheRoot == null) {
            sb.append("- Java: nothing to repair here").append(range.error != null ? " (the version setting itself is invalid: " + range.error + ")" : "").append('\n');
            return;
        }
        var chooser = new JavaChooser();
        chooser.hasConsole = hasConsole;
        chooser.guiMode = guiMode;
        chooser.packageType = config.javaType.isEmpty() ? "jre" : config.javaType;
        var h = chooser.choose(range, exeName, false);
        for (var c : chooser.seen) {
            if (!c.usable() && c.source.equals("jr cache") && !c.reject.contains("cannot run") && slotName(Paths.fileNameOf(c.home))) {
                Dirs.removeDirTree(c.home);
                sb.append("- Removed a damaged Java from jr's cache (").append(c.reject).append("): ").append(c.home).append('\n');
            }
        }
        if (h != null && chooser.rule.startsWith("exact")) {
            sb.append("- Java ").append(range.preferred).append(" is installed: ").append(h.home).append('\n');
        } else if (config.javaAutoInstall != 0) {
            var home = JavaInstall.tryInstall(range.preferred, false, "Repair: Java " + range.preferred + " is not installed.",
                    hasConsole, guiMode, false, cacheRoot, chooser.packageType);
            sb.append(home != null ? "- Java " + range.preferred + " is installed: " + home : "- Java " + range.preferred + " was not downloaded")
                    .append('\n');
        }
    }
}
