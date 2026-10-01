package jarrunner.jr.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/** Copies the bundled jr exe for each architecture and has jr itself bake the jrc-json, icon and
 *  version into it (-Xjr:edit), so the bake gets jr's own config check. A copy of the x86_64 exe
 *  does the editing for every architecture: resource editing treats the target as data. */
final class ExeStamper {
    private final Log log;
    private final File dir;
    private final String name;
    private final File json;
    private final File icon;
    private final String version;
    private final Map<String, String> strings;

    ExeStamper(Log log, File dir, String name, File json, File icon, String version,
            Map<String, String> strings) {
        this.strings = strings;
        this.log = log;
        this.dir = dir;
        this.name = name;
        this.json = json;
        this.icon = icon;
        this.version = version;
    }

    /** Builds one exe per architecture; returns them by architecture, in the order given. */
    Map<String, File> run(List<String> archs, String installDir) throws Exception {
        var editor = extract("x86_64", new File(dir, "jr-editor.exe"));
        var built = new LinkedHashMap<String, File>();
        File host = null;
        try {
            for (var arch : archs) {
                var out = new File(dir, archs.size() == 1 ? name + ".exe" : name + "-windows-" + arch + ".exe");
                extract(arch, out);
                stamp(editor, out);
                log.info("exe: " + out);
                built.put(arch, out);
                if (arch.equals("x86_64") || host == null) host = out;
            }
        } finally {
            Files.deleteIfExists(editor.toPath());
        }
        if (installDir != null && !installDir.isBlank()) install(host, new File(installDir, name + ".exe"));
        return built;
    }

    private File extract(String arch, File out) throws Exception {
        try (var in = getClass().getResourceAsStream("/jarrunner/jr/maven/jr-windows-" + arch + ".exe")) {
            if (in == null) throw new MojoExecutionException("This plugin bundles no jr exe for " + arch + " (x86_64, arm64)");
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private void stamp(File editor, File out) throws Exception {
        var cmd = new ArrayList<>(List.of(editor.getPath(), "-Xjr:edit=" + out.getPath(),
                "-Xjr:resource.RCDATA.JRC=" + json.getPath(), "-Xjr:version=" + numericVersion(version),
                "-Xjr:version.ProductVersion=" + version));
        strings.forEach((k, v) -> cmd.add("-Xjr:version." + k + "=" + v));
        if (icon != null) cmd.add("-Xjr:icon=" + icon.getPath());
        // A freshly written exe is sometimes still held by an on-write virus scan; jr reports error 32.
        for (var attempt = 1; ; attempt++) {
            var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            var output = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0) {
                for (var line : output.split("\\R")) if (line.startsWith("warning:")) log.warn(line);
                return;
            }
            if (attempt >= 5 || !output.contains("error 32")) {
                throw new MojoExecutionException("jr could not build " + out.getName() + ":\n" + output);
            }
            Thread.sleep(500);
        }
    }

    /** The Windows version resource holds four numbers: 1.2.3-SNAPSHOT becomes 1.2.3.0. */
    static String numericVersion(String v) {
        var parts = new ArrayList<String>();
        for (var p : v.split("[.\\-+]")) {
            if (parts.size() == 4 || !p.matches("\\d+")) break;
            parts.add(p);
        }
        while (parts.size() < 4) parts.add("0");
        return String.join(".", parts);
    }

    /** An exe that is running cannot be overwritten but can be renamed: move it to .jr-replaced,
     *  which jr deletes on its next launch (the same convention as -Xjr:update). */
    private void install(File exe, File target) throws Exception {
        target.getParentFile().mkdirs();
        try {
            Files.copy(exe.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.FileSystemException busy) {
            var aside = new File(target.getPath() + ".jr-replaced");
            Files.deleteIfExists(aside.toPath());
            Files.move(target.toPath(), aside.toPath());
            Files.copy(exe.toPath(), target.toPath());
        }
        log.info("installed: " + target);
    }
}
