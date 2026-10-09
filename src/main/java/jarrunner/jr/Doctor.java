package jarrunner.jr;

/** PRP-31 -Xjr:doctor: what jr would do on this machine and why, changing nothing. -Xjr:repair (Repair)
 *  acts on the same findings. */
public final class Doctor {
    private Doctor() {}

    static String report(Config config, String configLabel, String exeName) {
        var sb = new StringBuilder();
        sb.append("jr doctor: ").append(ExeInfo.fullPath()).append('\n');
        if (!config.appId.isEmpty() || !config.appVersion.isEmpty()) {
            sb.append("App: ").append(config.appId).append(' ').append(config.appVersion).append('\n');
        }
        sb.append("Config: ").append(configLabel).append('\n');
        if (config.loadError != null) {
            sb.append("  CANNOT BE READ: ").append(config.loadError).append('\n');
        }
        sb.append("Launch: ").append(config.useJvmDll == 1 ? "in-process (jvm=dll)" : "child process (jvm=exe)")
                .append(", AOT ").append(config.enableAOT != 0 ? "on" : "off").append('\n');
        if (!config.vmArgs.isBlank()) {
            sb.append("vm.args: ").append(config.vmArgs).append('\n');
        }
        java(sb, config, exeName);
        jar(sb, config);
        files(sb);
        return sb.toString();
    }

    private static void java(StringBuilder sb, Config config, String exeName) {
        var range = JavaRange.of(config);
        if (range.error != null) {
            sb.append("Java wanted: INVALID SETTING: ").append(range.error).append('\n');
            return;
        }
        sb.append("Java wanted: ").append(range.describe()).append(range.note != null ? " (" + range.note + ")" : "").append('\n');
        if (!config.javaHome.isEmpty()) {
            sb.append("java.home is set, so jr uses it as is:\n- ").append(JavaHome.inspect(config.javaHome, "java.home", exeName).describe()).append('\n');
            return;
        }
        var chooser = new JavaChooser();
        var h = chooser.choose(range, exeName, false);
        sb.append("Java found (").append(exeName).append("):\n").append(chooser.report());
        var exact = h != null && chooser.rule.startsWith("exact");
        if (exact) {
            sb.append("A launch would use: ").append(h.home).append(" (").append(chooser.rule).append(")\n");
        } else if (config.javaAutoInstall != 0) {
            sb.append("A launch would offer to download Java ").append(range.preferred).append(" into ").append(JavaInstall.cacheRoot())
                    .append(h != null ? "; if that is declined or fails: " + h.home : "").append('\n');
        } else {
            sb.append("A launch would use: ").append(h != null ? h.home + " (" + chooser.rule + ")" : "NOTHING SUITABLE (java.autoinstall=false)").append('\n');
        }
        if (h != null) {
            AotCache.jvmTag = AotCache.jvmTag(h);
        }
    }

    private static void jar(StringBuilder sb, Config config) {
        var remote = RemoteJar.localPath(config);
        var jar = remote != null ? remote : JarPath.fromArgsString(config.javaArgs);
        if (jar == null || jar.isEmpty()) {
            sb.append("Jar: none named (java.args: ").append(config.javaArgs).append(")\n");
            return;
        }
        var size = FileInfo.size(jar);
        sb.append("Jar: ").append(jar).append(size >= 0 ? " (" + size + " bytes)" : remote != null ? " (not downloaded yet)" : " (MISSING)").append('\n');
        if (size < 0) {
            return;
        }
        if (remote != null) {
            var changed = JarCheck.changedSinceVerified(config, jar, AsciiStr.lower(config.runSha256));
            sb.append("  check: ").append(changed == null ? "unchanged since verified" : "CHANGED since it was verified; -Xjr:repair deletes it").append('\n');
        }
        var cache = AotCache.buildCacheName(jar);
        var others = Dirs.matchCount(Paths.dirOf(jar), AotCache.baseName(jar) + ".*.aot") - (FileIo.exists(cache) ? 1 : 0);
        sb.append("AOT cache: ").append(cache).append(FileIo.exists(cache) ? " (present)" : " (not created yet)")
                .append(others > 0 ? "; " + others + " other cache(s) for this jar, made by another Java or jar build" : "").append('\n');
    }

    private static void files(StringBuilder sb) {
        var exe = ExeInfo.baseNameNoExt();
        var runs = JrDirs.of("runs");
        if (Dirs.matchCount(runs, exe + "-*.stderr.txt") > 0) {
            sb.append("Last GUI-mode Java output: ").append(runs).append('\\').append(exe).append("-*.stderr.txt\n");
        }
        var crash = JrDirs.of("crash");
        var crashes = crash == null ? 0 : Dirs.matchCount(crash, exe + "-hs_err_pid*.log");
        if (crashes > 0) {
            sb.append("Java crash logs: ").append(crashes).append(" in ").append(crash).append('\n');
        }
        var reports = JrDirs.of("reports");
        var count = reports == null ? 0 : Dirs.matchCount(reports, exe + "-*.txt");
        if (count > 0) {
            sb.append("Error reports: ").append(count).append(" in ").append(reports).append('\n');
        }
    }
}
