package jarrunner.examples.hello;

/** The smallest app a jr exe can carry. It prints what jr told it, so each step of the
 *  distribution test (first-run download, update check, self-update) is visible. */
public class Hello {
    public static void main(String[] args) {
        var version = System.getProperty("io.github.jarrunner.jr.app.version", "(not started by jr)");
        System.out.println("Hello from a jr exe. Version " + version + ", Java " + Runtime.version().feature() + ".");
        System.out.println("Running as " + System.getProperty("io.github.jarrunner.jr.exe", "plain java"));
        if (args.length > 0) System.out.println("Arguments: " + String.join(" ", args));
        System.out.println("New in 1.1.0: this line. If you see it, -Xjr:update worked.");
    }
}
