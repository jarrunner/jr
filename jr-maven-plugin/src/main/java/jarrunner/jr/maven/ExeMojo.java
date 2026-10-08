package jarrunner.jr.maven;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
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
    /** PRP-31: the oldest, the wanted and the newest Java major; an alternative to javaVersion, not both. */
    @Parameter Integer javaMin;
    @Parameter Integer javaPreferred;
    @Parameter Integer javaMax;
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
    /** May contain {sha8}, replaced by the first 8 hex digits of the jar's SHA-256, so each build of
     *  the jar has its own file name and a stale exe asks for a file that is not there. */
    @Parameter String jarUrl;
    String resolvedJarUrl;
    @Parameter String updateUrl;
    @Parameter String updateChannel;
    /** Who supports the app (PRP-31), shown in jr's error dialog; each defaults from the pom, see SupportSection. */
    @Parameter String supportName;
    @Parameter String supportEmail;
    @Parameter String supportIssues;
    @Parameter String supportUrl;
    /** Per-run jar check for a downloaded jar: crc32 (default), sha256 or none. */
    @Parameter(property = "jr.jarVerify") String jarVerify;
    /** x86_64 and/or arm64. One architecture gives name.exe; several give name-windows-ARCH.exe. */
    @Parameter List<String> architectures;
    /** Version resource strings. fileDescription is what Task Manager shows (default: name). */
    @Parameter String fileDescription;
    @Parameter String productName;
    @Parameter String companyName;
    @Parameter String copyright;
    /** The application manifest for the exe. Default: the bundled one, the same kind java.exe carries: Windows 7
     *  to 11, asInvoker, Common Controls 6 and per-monitor DPI awareness, so Windows does not treat the exe as a
     *  legacy program (no compatibility popups, no blurry bitmap-stretched windows on a scaled display). */
    @Parameter File manifest;
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
    /** windows and/or macos (PRP-36). Default windows. The Windows exes are stamped with Windows' own resource
     *  calls, so they are built only on Windows; the macOS outputs are built on any OS. */
    @Parameter(property = "jr.platforms") List<String> platforms;
    /** binary (one file, config embedded, no icon) and/or app (an .app bundle with icon, plus a zip of it). Default binary. */
    @Parameter(property = "jr.macosForms") List<String> macosForms;
    /** arm64, x86_64 or universal (one fat binary for both). Default universal. */
    @Parameter(property = "jr.macosArchitecture", defaultValue = "universal") String macosArchitecture;
    /** The app's icon on macOS: a .png, .icns or PNG-entry .ico. Default: icon. With neither, the app has no icon. */
    @Parameter File macosIcon;
    /** Leave the icon out of the .app even when icon is set. */
    @Parameter(property = "jr.macosNoIcon", defaultValue = "false") boolean macosNoIcon;
    /** The .app's name in Finder and the Dock. Default: productName, else name. */
    @Parameter String macosDisplayName;
    /** CFBundleIdentifier. Default: appId with ':' as '.' (io.github.me:tool becomes io.github.me.tool). */
    @Parameter String macosBundleId;
    /** For source=path, the jar inside the .app (Contents/Resources) instead of the build machine's path. */
    String jarPathOverride;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("jr:exe skipped");
            return;
        }
        var plats = platforms == null || platforms.isEmpty() ? List.of("windows") : platforms;
        for (var p : plats) {
            if (!p.equals("windows") && !p.equals("macos")) throw new MojoExecutionException("platforms takes windows and/or macos, not " + p);
        }
        var windows = plats.contains("windows");
        if (windows && !System.getProperty("os.name", "").startsWith("Windows")) {
            getLog().warn("jr:exe stamps Windows exes with Windows' own resource calls, so they are built only on Windows. Skipped them.");
            windows = false;
        }
        try {
            outputDirectory.mkdirs();
            var json = new File(outputDirectory, name + ".jrc.json");
            Files.writeString(json.toPath(), mapper().writeValueAsString(config()));
            getLog().info("jrc-json: " + json);
            Map<String, File> exes = new LinkedHashMap<>();
            if (windows) {
                var stamper = new ExeStamper(getLog(), outputDirectory, name, json, icon, appVersion, versionStrings());
                stamper.manifest = manifest;
                exes = stamper.run(archs(), installDir);
            }
            var macFiles = plats.contains("macos") ? macos() : List.<File>of();
            if (release != null ? release : source.equals("url")) writeRelease(exes, macFiles);
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
        if (javaVersion != null || javaMin != null || javaPreferred != null || javaMax != null || javaType != null || javaHome != null || javaAutoinstall != null) {
            c.java(new JavaSection().version(javaVersion).min(javaMin).preferred(javaPreferred).max(javaMax).type(javaType).home(javaHome).autoinstall(javaAutoinstall));
        }
        if (jvmMode != null || vmArgs != null || javaArgs != null) {
            c.jvm(new JvmSection().mode(jvmMode).vmArgs(vmArgs).javaArgs(javaArgs));
        }
        if (updateUrl != null) c.update(new UpdateSection().url(updateUrl).channel(updateChannel));
        c.support(SupportSection.of(project, supportName, supportEmail, supportIssues, supportUrl));
        if (javaArgs == null) {
            var digests = digests(jar);
            resolvedJarUrl = jarUrl == null ? null : jarUrl.replace("{sha8}", digests[0].substring(0, 8));
            var downloaded = !source.equals("path");
            c.jar(new JarSection().sha256(digests[0]).crc32(downloaded ? digests[1] : null)
                    .verify(downloaded ? jarVerify : null).sources(List.of(jarSource())));
        }
        return c;
    }

    /** The macOS outputs (PRP-36); returns the files a release publishes (the binary, the app's zip). */
    private List<File> macos() throws Exception {
        var mac = new MacOutputs(getLog(), outputDirectory, name);
        mac.version = appVersion;
        mac.displayName = macosDisplayName != null ? macosDisplayName : productName != null ? productName : name;
        mac.bundleId = macosBundleId != null ? macosBundleId : appId.replace(':', '.');
        mac.icon = macosNoIcon ? null : macosIcon != null ? macosIcon : icon;
        var arch = macosArchitecture == null ? "universal" : macosArchitecture;
        var compact = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        var files = new ArrayList<File>();
        for (var form : MacOutputs.forms(macosForms)) {
            if (form.equals("binary")) {
                // source=path with no jarPath: on a Mac the build's own jar, as on Windows; built elsewhere, that
                // path means nothing on a Mac, so the jar is expected beside the binary
                var local = javaArgs == null && source.equals("path") && jarPath == null;
                var onMac = System.getProperty("os.name", "").startsWith("Mac");
                jarPathOverride = local && !onMac ? jar.getName() : null;
                if (jarPathOverride != null) getLog().info("macOS binary: expects " + jar.getName() + " beside it (set jarPath, or source maven/url, to change that)");
                var cfg = compact.writeValueAsString(config());
                jarPathOverride = null;
                files.add(mac.binary(arch, cfg));
            } else {
                // A local jar travels inside the bundle; a maven or url jar is downloaded by jr as usual.
                var bundled = javaArgs == null && source.equals("path") && jarPath == null;
                jarPathOverride = bundled ? "../Resources/" + jar.getName() : null;
                var cfg = compact.writeValueAsString(config());
                jarPathOverride = null;
                files.add(mac.app(arch, cfg, bundled ? jar : null));
            }
        }
        return files;
    }

    private JarSource jarSource() throws MojoExecutionException {
        return switch (source) {
            case "path" -> new JarSource().path(jarPathOverride != null ? jarPathOverride : jarPath != null ? jarPath : jar.getAbsolutePath());
            case "maven" -> new JarSource().maven(mavenCoords != null ? mavenCoords
                    : project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
            case "url" -> {
                if (jarUrl == null) throw new MojoExecutionException("source=url needs jarUrl");
                yield new JarSource().url(resolvedJarUrl);
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

    private void writeRelease(Map<String, File> exes, List<File> macFiles) throws Exception {
        var base = releaseBaseUrl;
        if (base == null && resolvedJarUrl != null) base = resolvedJarUrl.substring(0, resolvedJarUrl.lastIndexOf('/') + 1);
        if (base == null || !base.startsWith("https://")) {
            throw new MojoExecutionException("release needs an https releaseBaseUrl (or a jarUrl to take its folder from)");
        }
        var withJar = javaArgs == null;
        // macOS files are published and listed in SHA256SUMS, but not in the update file: self-update is Windows-only
        var extra = new ArrayList<File>(releaseFiles == null ? List.of() : releaseFiles);
        extra.addAll(macFiles);
        var jarName = resolvedJarUrl != null && source.equals("url") ? resolvedJarUrl.substring(resolvedJarUrl.lastIndexOf('/') + 1) : jar.getName();
        new ReleaseWriter(getLog(), releaseDirectory, name, appId, appVersion, updateChannel, base)
                .write(exes, withJar ? jar : null, jarName, extra, updateMergeFrom);
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
