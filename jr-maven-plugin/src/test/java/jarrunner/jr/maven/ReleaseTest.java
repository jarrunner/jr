package jarrunner.jr.maven;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class ReleaseTest {

    @TempDir Path dir;

    ExeMojo urlMojo() throws Exception {
        var m = new ExeMojo();
        m.jar = TestApp.jar(dir);
        m.name = "hello";
        m.appId = "io.github.example:hello";
        m.appVersion = "1.1.0";
        m.source = "url";
        m.jarUrl = "https://github.com/example/hello/releases/download/v1.1.0/hello-win.jar";
        m.outputDirectory = dir.resolve("out").toFile();
        m.releaseDirectory = dir.resolve("out/release").toFile();
        m.aot = false;
        return m;
    }

    @Test void urlBuildWritesTheReleaseFolder() throws Exception {
        var m = urlMojo();
        var extra = dir.resolve("hello-mac.jar");
        Files.writeString(extra, "mac jar");
        m.releaseFiles = List.of(extra.toFile());
        m.execute();
        var rel = m.releaseDirectory.toPath();
        var names = List.of(rel.toFile().list());
        assertTrue(names.containsAll(List.of("hello.exe", "hello-win.jar", "hello-mac.jar", "version.txt", "SHA256SUMS", "hello.update.json")), names.toString());
        assertArrayEquals(Files.readAllBytes(m.jar.toPath()), Files.readAllBytes(rel.resolve("hello-win.jar")));
        assertEquals("1.1.0", Files.readString(rel.resolve("version.txt")));

        var sums = Files.readString(rel.resolve("SHA256SUMS"));
        assertFalse(sums.contains("\r"));
        for (var n : List.of("hello.exe", "hello-win.jar", "hello-mac.jar", "version.txt")) {
            assertTrue(sums.contains(ReleaseWriter.sha256(rel.resolve(n).toFile()) + "  " + n + "\n"), n + " in\n" + sums);
        }
        assertFalse(sums.contains("SHA256SUMS"));

        var u = ExeMojo.mapper().readTree(rel.resolve("hello.update.json").toFile());
        assertEquals(1, u.get("format").asInt());
        assertEquals("io.github.example:hello", u.get("app").asText());
        assertEquals("1.1.0", u.path("channels").path("stable").asText());
        var exe = u.path("releases").get(0).path("exe").path("windows-x86_64");
        assertEquals(ReleaseWriter.sha256(rel.resolve("hello.exe").toFile()), exe.path("sha256").asText());
        assertEquals("https://github.com/example/hello/releases/download/v1.1.0/hello.exe", exe.path("urls").get(0).asText());
    }

    @Test void mergesAnExistingUpdateFile() throws Exception {
        var old = dir.resolve("old.json");
        Files.writeString(old, "{\"format\":1,\"app\":\"io.github.example:hello\",\"channels\":{\"stable\":\"1.0.0\",\"beta\":\"1.1.0-beta.1\"},"
                + "\"releases\":[{\"version\":\"1.1.0\",\"notes\":\"a stale entry for this version\"},"
                + "{\"version\":\"1.1.0-beta.1\"},{\"version\":\"1.0.0\",\"notes\":\"first\"}]}");
        var m = urlMojo();
        m.updateMergeFrom = old.toFile();
        m.execute();
        var u = ExeMojo.mapper().readTree(m.releaseDirectory.toPath().resolve("hello.update.json").toFile());
        assertEquals("1.1.0", u.path("channels").path("stable").asText());
        assertEquals("1.1.0-beta.1", u.path("channels").path("beta").asText());
        var versions = new java.util.ArrayList<String>();
        u.path("releases").forEach(r -> versions.add(r.path("version").asText()));
        assertEquals(List.of("1.1.0", "1.1.0-beta.1", "1.0.0"), versions);
        assertFalse(u.path("releases").get(0).has("notes"), "the stale entry for 1.1.0 is replaced, not kept");
        assertEquals("first", u.path("releases").get(2).path("notes").asText());
    }

    @Test void aMissingExtraFileFailsTheBuild() throws Exception {
        var m = urlMojo();
        m.releaseFiles = List.of(dir.resolve("not-built-yet.jar").toFile());
        var e = assertThrows(MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("not-built-yet.jar"), e.getMessage());
    }

    @Test void bothArchitecturesGetTheirPlatformKeys() throws Exception {
        var m = urlMojo();
        m.architectures = List.of("x86_64", "arm64");
        m.execute();
        var u = ExeMojo.mapper().readTree(m.releaseDirectory.toPath().resolve("hello.update.json").toFile());
        var exe = u.path("releases").get(0).path("exe");
        assertTrue(exe.path("windows-x86_64").path("urls").get(0).asText().endsWith("/hello-windows-x86_64.exe"));
        assertTrue(exe.path("windows-aarch64").path("urls").get(0).asText().endsWith("/hello-windows-arm64.exe"));
    }

    @Test void aPathBuildWritesNoRelease() throws Exception {
        var m = urlMojo();
        m.source = "path";
        m.execute();
        assertFalse(new File(m.releaseDirectory, "SHA256SUMS").exists());
    }
}
