package jarrunner.jr.maven;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** jr:exe - builds the app's jr launcher exe(s) in the package phase (PRP-30). Hashes the jar,
 *  writes the jrc-json, has jr itself check and bake it (plus icon and version) into the jr exes
 *  bundled in this plugin, and copies the result to installDir when one is set. installDir is meant
 *  to come from outside the project (settings.xml profile or -Djr.installDir), never from a pom. */
@Mojo(name = "exe", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class ExeMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true) MavenProject project;
    @Parameter(property = "jr.jar", defaultValue = "${project.build.directory}/${project.build.finalName}.jar") File jar;
    @Parameter(property = "jr.name", defaultValue = "${project.artifactId}") String name;
    @Parameter(defaultValue = "${project.build.directory}/jr") File outputDirectory;
    @Parameter(property = "jr.installDir") String installDir;
    @Parameter(property = "jr.skip", defaultValue = "false") boolean skip;
    @Parameter File icon;
    @Parameter(defaultValue = "${project.groupId}:${project.artifactId}") String appId;
    @Parameter(defaultValue = "${project.version}") String appVersion;
    @Parameter String javaVersion;
    @Parameter String javaType;
    @Parameter String javaHome;
    @Parameter Boolean javaAutoinstall;
    @Parameter String jvmMode;
    @Parameter List<String> vmArgs;
    @Parameter List<String> appArgs;
    @Parameter String javaArgs;
    @Parameter Boolean aot;
    /** Where the exe finds its jar: path (local build, the default), maven, or url. */
    @Parameter(property = "jr.source", defaultValue = "path") String source;
    @Parameter String jarPath;
    @Parameter String mavenCoords;
    @Parameter String jarUrl;
    @Parameter String updateUrl;
    @Parameter String updateChannel;
    /** Per-run jar check for a downloaded jar: crc32 (default), sha256 or none. */
    @Parameter(property = "jr.jarVerify") String jarVerify;
    /** x86_64 and/or arm64. One architecture gives name.exe; several give name-windows-ARCH.exe. */
    @Parameter List<String> architectures;
    /** Version resource strings. fileDescription is what Task Manager shows (default: name). */
    @Parameter String fileDescription;
    @Parameter String productName;
    @Parameter String companyName;
    @Parameter String copyright;
    /** Write the release folder (exes, jar, version.txt, releaseFiles, SHA256SUMS, update file).
     *  Default: on for source=url. */
    @Parameter(property = "jr.release") Boolean release;
    @Parameter(defaultValue = "${project.build.directory}/jr/release") File releaseDirectory;
    /** Where the release's files are downloaded from; default the folder of jarUrl. */
    @Parameter(property = "jr.releaseBaseUrl") String releaseBaseUrl;
    /** Extra files published with the release and listed in SHA256SUMS; each must exist. */
    @Parameter List<File> releaseFiles;
    /** An existing update file whose channels and other releases the new one keeps. */
    @Parameter(property = "jr.updateMergeFrom") File updateMergeFrom;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("jr:exe skipped");
            return;
        }
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            getLog().warn("jr:exe stamps Windows exes with Windows' own resource calls, so it runs only on Windows. Skipped.");
            return;
        }
        try {
            outputDirectory.mkdirs();
            var json = new File(outputDirectory, name + ".jrc.json");
            Files.writeString(json.toPath(), mapper().writeValueAsString(config()));
            getLog().info("jrc-json: " + json);
            var exes = new ExeStamper(getLog(), outputDirectory, name, json, icon, appVersion, versionStrings()).run(archs(), installDir);
            if (release != null ? release : source.equals("url")) writeRelease(exes);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("jr:exe failed: " + e.getMessage(), e);
        }
    }

    JrcConfig config() throws Exception {
        var c = new JrcConfig()
                .app(new AppSection().id(appId).name(name).version(appVersion).args(appArgs))
                .aot(aot);
        if (javaVersion != null || javaType != null || javaHome != null || javaAutoinstall != null) {
            c.java(new JavaSection().version(javaVersion).type(javaType).home(javaHome).autoinstall(javaAutoinstall));
        }
        if (jvmMode != null || vmArgs != null || javaArgs != null) {
            c.jvm(new JvmSection().mode(jvmMode).vmArgs(vmArgs).javaArgs(javaArgs));
        }
        if (updateUrl != null) c.update(new UpdateSection().url(updateUrl).channel(updateChannel));
        if (javaArgs == null) {
            var digests = digests(jar);
            var downloaded = !source.equals("path");
            c.jar(new JarSection().sha256(digests[0]).crc32(downloaded ? digests[1] : null)
                    .verify(downloaded ? jarVerify : null).sources(List.of(jarSource())));
        }
        return c;
    }

    private JarSource jarSource() throws MojoExecutionException {
        return switch (source) {
            case "path" -> new JarSource().path(jarPath != null ? jarPath : jar.getAbsolutePath());
            case "maven" -> new JarSource().maven(mavenCoords != null ? mavenCoords
                    : project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
            case "url" -> {
                if (jarUrl == null) throw new MojoExecutionException("source=url needs jarUrl");
                yield new JarSource().url(jarUrl);
            }
            default -> throw new MojoExecutionException("source must be path, maven or url, not " + source);
        };
    }

    private Map<String, String> versionStrings() {
        var m = new LinkedHashMap<String, String>();
        m.put("FileDescription", fileDescription != null ? fileDescription : name);
        m.put("ProductName", productName != null ? productName : name);
        m.put("InternalName", name);
        m.put("OriginalFilename", name + ".exe");
        if (companyName != null) m.put("CompanyName", companyName);
        if (copyright != null) m.put("LegalCopyright", copyright);
        return m;
    }

    private void writeRelease(Map<String, File> exes) throws Exception {
        var base = releaseBaseUrl;
        if (base == null && jarUrl != null) base = jarUrl.substring(0, jarUrl.lastIndexOf('/') + 1);
        if (base == null || !base.startsWith("https://")) {
            throw new MojoExecutionException("release needs an https releaseBaseUrl (or a jarUrl to take its folder from)");
        }
        var withJar = javaArgs == null;
        var jarName = jarUrl != null && source.equals("url") ? jarUrl.substring(jarUrl.lastIndexOf('/') + 1) : jar.getName();
        new ReleaseWriter(getLog(), releaseDirectory, name, appId, appVersion, updateChannel, base)
                .write(exes, withJar ? jar : null, jarName, releaseFiles == null ? List.of() : releaseFiles, updateMergeFrom);
    }

    private List<String> archs() {
        return architectures == null || architectures.isEmpty() ? List.of("x86_64") : architectures;
    }

    /** SHA-256 and CRC32 (the same CRC-32 as ntdll's RtlComputeCrc32, which jr uses) in one pass. */
    private static String[] digests(File f) throws Exception {
        if (!f.isFile()) throw new MojoExecutionException("No jar to launch at " + f + " (set jar, or run after the shade plugin)");
        var md = MessageDigest.getInstance("SHA-256");
        var crc = new CRC32();
        try (var in = Files.newInputStream(f.toPath())) {
            var buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
                crc.update(buf, 0, n);
            }
        }
        return new String[]{HexFormat.of().formatHex(md.digest()), String.format("%08x", crc.getValue())};
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
