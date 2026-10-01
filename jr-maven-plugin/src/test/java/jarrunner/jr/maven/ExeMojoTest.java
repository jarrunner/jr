package jarrunner.jr.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ExeMojoTest {

    @TempDir Path dir;

    ExeMojo mojo(File jar) {
        var m = new ExeMojo();
        m.jar = jar;
        m.name = "hello";
        m.appId = "io.github.example:hello";
        m.appVersion = "1.2.3-SNAPSHOT";
        m.source = "path";
        m.outputDirectory = dir.resolve("out").toFile();
        m.aot = false;
        return m;
    }

    @Test void numericVersion() {
        assertEquals("1.2.3.0", ExeStamper.numericVersion("1.2.3-SNAPSHOT"));
        assertEquals("2.0.0.0", ExeStamper.numericVersion("2.0"));
        assertEquals("1.2.3.4", ExeStamper.numericVersion("1.2.3.4.5"));
        assertEquals("0.0.0.0", ExeStamper.numericVersion("beta"));
    }

    @Test void pathSourceCarriesNoDownloadChecks() throws Exception {
        var jar = TestApp.jar(dir);
        var json = ExeMojo.mapper().writeValueAsString(mojo(jar).config());
        assertTrue(json.contains("\"sha256\" : \"" + sha256(jar) + "\""), json);
        assertTrue(json.contains("\"path\""), json);
        assertFalse(json.contains("crc32"), json);
        assertFalse(json.contains("verify"), json);
    }

    @Test void urlSourceCarriesCrc32AndVerify() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.source = "url";
        m.jarUrl = "https://example.org/hello.jar";
        m.jarVerify = "sha256";
        var json = ExeMojo.mapper().writeValueAsString(m.config());
        assertTrue(json.contains("\"url\" : \"https://example.org/hello.jar\""), json);
        assertTrue(json.matches("(?s).*\"crc32\" : \"[0-9a-f]{8}\".*"), json);
        assertTrue(json.contains("\"verify\" : \"sha256\""), json);
    }

    @Test void javaArgsLaunchHasNoJarSection() throws Exception {
        var m = mojo(new File("not-needed.jar"));
        m.javaArgs = "-cp lib/* com.example.Main";
        var json = ExeMojo.mapper().writeValueAsString(m.config());
        assertFalse(json.contains("\"jar\""), json);
        assertTrue(json.contains("\"javaArgs\" : \"-cp lib/* com.example.Main\""), json);
    }

    @Test void urlSourceNeedsAUrl() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.source = "url";
        assertThrows(MojoExecutionException.class, m::config);
        m.source = "ftp";
        assertThrows(MojoExecutionException.class, m::config);
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void buildsARunnableExeWithNoIconUnlessOneIsGiven() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.appArgs = List.of("two words");
        m.execute();
        var exe = new File(m.outputDirectory, "hello.exe");
        assertTrue(exe.isFile());
        var res = TestApp.run(List.of(exe.getPath(), "-Xjr:list-resources=" + exe.getPath()));
        assertFalse(res.contains("ICON"), res);
        assertTrue(res.contains("VERSION"), res);
        assertTrue(res.contains("RCDATA"), res);
        var out = TestApp.run(List.of(exe.getPath(), "more"));
        assertTrue(out.startsWith("0\n"), out);
        assertTrue(out.contains("hello two words,more version=1.2.3-SNAPSHOT"), out);
        assertTrue(Files.readString(m.outputDirectory.toPath().resolve("hello.jrc.json")).contains("\"version\" : \"1.2.3-SNAPSHOT\""));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void stampsTheGivenIconAndInstalls() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.icon = icon();
        m.architectures = List.of("x86_64", "arm64");
        m.installDir = dir.resolve("bin").toString();
        m.execute();
        var x64 = new File(m.outputDirectory, "hello-windows-x86_64.exe");
        assertTrue(x64.isFile());
        assertTrue(new File(m.outputDirectory, "hello-windows-arm64.exe").isFile());
        var res = TestApp.run(List.of(x64.getPath(), "-Xjr:list-resources=" + x64.getPath()));
        assertTrue(res.contains("ICON"), res);
        var installed = dir.resolve("bin").resolve("hello.exe");
        assertArrayEquals(Files.readAllBytes(x64.toPath()), Files.readAllBytes(installed));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void aBadConfigIsRefusedByJr() throws Exception {
        var m = mojo(TestApp.jar(dir));
        m.jvmMode = "sideways";
        var e = assertThrows(MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("jvm.mode"), e.getMessage());
    }

    /** A real 256px PNG-compressed icon: jr's own, from the repository. */
    static File icon() {
        var f = new File("../icon/jr-icon.ico");
        assertTrue(f.isFile(), "expected jr's icon at " + f.getAbsolutePath());
        return f;
    }

    static String sha256(File f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath())));
    }
}
