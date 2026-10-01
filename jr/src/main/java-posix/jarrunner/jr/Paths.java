package jarrunner.jr;

/** Windows path string helpers - pure Java, no filesystem access. */
public final class Paths {
    private Paths() {}

    public static String dirOf(String path) {
        var slash = lastSlash(path);
        return slash < 0 ? "" : path.substring(0, slash);
    }

    public static String fileNameOf(String path) {
        var slash = lastSlash(path);
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public static String baseNameNoExt(String path) {
        var name = fileNameOf(path);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static int lastSlash(String path) {
        return Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
    }
}
