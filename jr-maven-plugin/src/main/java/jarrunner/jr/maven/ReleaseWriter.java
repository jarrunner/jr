package jarrunner.jr.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/** The files a release publishes, in one folder: the exe(s), the jar under the name its url
 *  downloads, version.txt, any extra files, SHA256SUMS, and the update file -Xjr:update reads
 *  (jr's update format 1). The jar is copied from the bytes the exe's sha256 was taken from, so
 *  what the exe expects and what the release carries cannot disagree. */
final class ReleaseWriter {
    final Log log;
    final File dir;
    final String name, appId, version, channel, baseUrl;

    ReleaseWriter(Log log, File dir, String name, String appId, String version, String channel, String baseUrl) {
        this.log = log;
        this.dir = dir;
        this.name = name;
        this.appId = appId;
        this.version = version;
        this.channel = channel == null || channel.isBlank() ? "stable" : channel;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    void write(Map<String, File> exes, File jar, String jarName, List<File> extra, File mergeFrom) throws Exception {
        for (var f : extra) {
            if (!f.isFile()) throw new MojoExecutionException("releaseFiles: " + f + " does not exist; build it before this goal runs");
        }
        if (dir.exists()) {
            for (var old : dir.listFiles()) Files.delete(old.toPath());
        }
        dir.mkdirs();
        for (var exe : exes.values()) copy(exe, exe.getName());
        if (jar != null) copy(jar, jarName);
        for (var f : extra) copy(f, f.getName());
        Files.writeString(new File(dir, "version.txt").toPath(), version);
        writeSums();
        var update = new File(dir, name + ".update.json");
        Files.writeString(update.toPath(), ExeMojo.mapper().writeValueAsString(updateFile(exes, mergeFrom)) + "\n");
        log.info("release: " + dir + " (update file " + update.getName() + ")");
    }

    private void copy(File from, String as) throws Exception {
        var to = new File(dir, as);
        if (to.exists()) throw new MojoExecutionException("release: two files would be named " + as);
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }

    /** "<sha256>  <name>" per file, sorted, LF: the format sha256sum writes and installers parse. */
    private void writeSums() throws Exception {
        var sums = new TreeMap<String, String>();
        for (var f : dir.listFiles()) sums.put(f.getName(), sha256(f));
        var b = new StringBuilder();
        sums.forEach((n, s) -> b.append(s).append("  ").append(n).append('\n'));
        Files.writeString(new File(dir, "SHA256SUMS").toPath(), b);
    }

    private JsonNode updateFile(Map<String, File> exes, File mergeFrom) throws Exception {
        var m = ExeMojo.mapper();
        var old = mergeFrom != null && mergeFrom.isFile() ? m.readTree(mergeFrom) : m.createObjectNode();
        if (old.has("format") && old.get("format").asInt() != 1) {
            throw new MojoExecutionException("updateMergeFrom: " + mergeFrom + " is update format " + old.get("format") + ", not 1");
        }
        var root = m.createObjectNode().put("format", 1).put("app", appId);
        var channels = root.putObject("channels");
        if (old.has("channels")) old.get("channels").properties().forEach(e -> channels.set(e.getKey(), e.getValue()));
        channels.put(channel, version);
        var releases = root.putArray("releases");
        var release = releases.addObject().put("version", version)
                .put("released", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        var exe = release.putObject("exe");
        for (var e : exes.entrySet()) {
            exe.putObject(platform(e.getKey())).put("sha256", sha256(e.getValue()))
                    .putArray("urls").add(baseUrl + e.getValue().getName());
        }
        if (old.get("releases") instanceof ArrayNode olds) {
            for (var r : olds) if (!version.equals(r.path("version").asText())) releases.add(r);
        }
        return root;
    }

    /** jr's platform keys: windows-x86_64 and windows-aarch64. */
    static String platform(String arch) {
        return "windows-" + (arch.equals("arm64") ? "aarch64" : arch);
    }

    static String sha256(File f) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f.toPath())) {
            var buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
