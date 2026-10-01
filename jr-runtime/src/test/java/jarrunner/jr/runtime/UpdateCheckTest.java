package jarrunner.jr.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckTest {

    static final String FILE = """
        {
          "format": 1,
          "app": "io.github.example:demo",
          "channels": { "stable": "1.0.0", "beta": "1.1.0-beta.1" },
          "releases": [
            { "version": "1.1.0-beta.1", "notes": "try the new \\"thing\\"" },
            { "version": "1.0.0", "notes": "one line" },
            { "version": "0.9.0", "retracted": "broke the relay" }
          ]
        }""";

    @Test void ownVersionIsTheChannels() {
        var r = UpdateCheck.decide(FILE, "1.0.0", "stable");
        assertEquals(UpdateCheck.Status.CURRENT, r.status());
        assertNull(r.retracted());
    }

    @Test void olderBuildSeesNewer() {
        var r = UpdateCheck.decide(FILE, "0.9.0", "stable");
        assertEquals(UpdateCheck.Status.NEWER, r.status());
        assertEquals("1.0.0", r.latest());
        assertEquals("one line", r.notes());
        assertEquals("broke the relay", r.retracted());
    }

    @Test void buildNewerThanItsChannelIsCurrent() {
        var r = UpdateCheck.decide(FILE, "1.1.0-beta.1", "stable");
        assertEquals(UpdateCheck.Status.CURRENT, r.status());
        assertTrue(r.message().contains("newer than stable"));
    }

    @Test void betaChannel() {
        var r = UpdateCheck.decide(FILE, "1.0.0", "beta");
        assertEquals(UpdateCheck.Status.NEWER, r.status());
        assertEquals("try the new \"thing\"", r.notes());
    }

    @Test void unknownOwnVersionSeesNewer() {
        // jr's rule: a version the file does not list is treated as older than the channel
        assertEquals(UpdateCheck.Status.NEWER, UpdateCheck.decide(FILE, "0.1-local", "stable").status());
    }

    @Test void errors() {
        assertEquals(UpdateCheck.Status.ERROR, UpdateCheck.decide(FILE, "1.0.0", "nightly").status());
        assertEquals(UpdateCheck.Status.ERROR, UpdateCheck.decide(FILE.replace("\"format\": 1", "\"format\": 2"), "1.0.0", "stable").status());
        assertEquals(UpdateCheck.Status.ERROR, UpdateCheck.decide(FILE.replace("\"format\": 1", "\"format\": \"1\""), "1.0.0", "stable").status());
        assertEquals(UpdateCheck.Status.ERROR, UpdateCheck.decide("{\"format\":1,", "1.0.0", "stable").status());
        assertEquals(UpdateCheck.Status.ERROR,
                UpdateCheck.decide("{\"format\":1,\"channels\":{\"stable\":\"2.0\"},\"releases\":[]}", "1.0.0", "stable").status());
    }

    @Test void cacheIsUsedForTheSameUrlVersionAndChannelOnly(@TempDir Path dir) throws Exception {
        var cache = dir.resolve("demo.json");
        var r = new UpdateCheck.Result(UpdateCheck.Status.NEWER, "1.0", "1.1", "stable", "a \"note\"\nline two", null, "m", Instant.now());
        var write = UpdateCheck.class.getDeclaredMethod("writeCache", Path.class, String.class, UpdateCheck.Result.class);
        write.setAccessible(true);
        write.invoke(null, cache, "https://unreachable.invalid/u.json", r);
        assertTrue(Files.isRegularFile(cache));

        var hit = UpdateCheck.checkAtMostEvery(Duration.ofDays(1), cache, "https://unreachable.invalid/u.json", "1.0", "stable");
        assertEquals(UpdateCheck.Status.NEWER, hit.status());
        assertEquals("a \"note\"\nline two", hit.notes());

        // a different version (after an update) or channel ignores the cache and checks again
        var miss = UpdateCheck.checkAtMostEvery(Duration.ofDays(1), cache, "https://unreachable.invalid/u.json", "1.1", "stable");
        assertEquals(UpdateCheck.Status.ERROR, miss.status());
        // an expired entry checks again too
        var stale = UpdateCheck.checkAtMostEvery(Duration.ZERO, cache, "https://unreachable.invalid/u.json", "1.0", "stable");
        assertEquals(UpdateCheck.Status.ERROR, stale.status());
        // and the error was not written over the good entry
        assertEquals(UpdateCheck.Status.NEWER,
                UpdateCheck.checkAtMostEvery(Duration.ofDays(1), cache, "https://unreachable.invalid/u.json", "1.0", "stable").status());
    }

    @Test void plainHttpIsRefused() {
        assertEquals(UpdateCheck.Status.ERROR, UpdateCheck.check("http://example.org/u.json", "1.0", "stable").status());
    }

    @Test void cacheFileNameIsSafe() {
        assertEquals("io.github.example_demo.json", UpdateCheck.cacheFile("io.github.example:demo").getFileName().toString());
    }

    @Test void jrAppReadsTheProperties() {
        try {
            System.setProperty("io.github.jarrunner.jr.exe", "C:\\tools\\demo.exe");
            System.setProperty("io.github.jarrunner.jr.app.version", "1.2");
            System.setProperty("io.github.jarrunner.jr.app.args.0", "two words");
            System.setProperty("io.github.jarrunner.jr.app.args.1", "x");
            assertTrue(JrApp.launchedByJr());
            assertEquals("demo", JrApp.exeName().orElseThrow());
            assertEquals("1.2", JrApp.appVersion().orElseThrow());
            assertEquals("stable", JrApp.updateChannel());
            assertEquals(java.util.List.of("two words", "x"), JrApp.configList("app.args"));
            var notice = UpdateCheck.notice(UpdateCheck.decide(FILE, "0.9.0", "stable"));
            assertEquals("demo 1.0.0 is available (this is 0.9.0): run demo -Xjr:update - this version was withdrawn: broke the relay", notice);
            assertNull(UpdateCheck.notice(UpdateCheck.decide(FILE, "1.0.0", "stable")));
        } finally {
            for (var k : new String[]{"exe", "app.version", "app.args.0", "app.args.1"}) System.clearProperty("io.github.jarrunner.jr." + k);
        }
        assertFalse(JrApp.launchedByJr());
        assertEquals(UpdateCheck.Status.NOT_CONFIGURED, UpdateCheck.check().status());
    }
}
