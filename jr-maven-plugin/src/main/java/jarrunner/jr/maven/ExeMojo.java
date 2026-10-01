package jarrunner.jr.maven;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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
    /** x86_64 and/or arm64. One architecture gives name.exe; several give name-windows-ARCH.exe. */
    @Parameter List<String> architectures;

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
            new ExeStamper(getLog(), outputDirectory, name, json, icon, appVersion).run(archs(), installDir);
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
        if (javaArgs == null) c.jar(new JarSection().sha256(sha256(jar)).sources(List.of(jarSource())));
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

    private List<String> archs() {
        return architectures == null || architectures.isEmpty() ? List.of("x86_64") : architectures;
    }

    private static String sha256(File f) throws Exception {
        if (!f.isFile()) throw new MojoExecutionException("No jar to launch at " + f + " (set jar, or run after the shade plugin)");
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f.toPath())) {
            var buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
