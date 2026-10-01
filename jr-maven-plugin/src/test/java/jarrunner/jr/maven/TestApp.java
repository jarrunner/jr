package jarrunner.jr.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/** A one-class app jar, compiled at test time, that prints its arguments and one jr property. */
final class TestApp {
    private TestApp() {}

    static final String SOURCE = String.join("\n",
            "public class Hello {",
            "  public static void main(String[] a) {",
            "    System.out.println(\"hello \" + String.join(\",\", a) + \" version=\"",
            "        + System.getProperty(\"io.github.jarrunner.jr.app.version\"));",
            "  }",
            "}");

    static File jar(Path dir) throws Exception {
        var src = dir.resolve("Hello.java");
        Files.writeString(src, SOURCE);
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac.run(null, null, null, "-d", dir.toString(), src.toString()) != 0) throw new IllegalStateException("javac failed");
        var mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Hello");
        var jar = dir.resolve("hello.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            out.putNextEntry(new JarEntry("Hello.class"));
            out.write(Files.readAllBytes(dir.resolve("Hello.class")));
            out.closeEntry();
        }
        return jar.toFile();
    }

    /** Runs a command and returns exit code and output (stdout and stderr together). */
    static String run(List<String> cmd) throws Exception {
        var p = new ProcessBuilder(new ArrayList<>(cmd)).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes());
        return p.waitFor() + "\n" + out;
    }
}
