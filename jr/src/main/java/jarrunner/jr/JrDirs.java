package jarrunner.jr;

/** jr's own folder, %USERPROFILE%\.jr (it already holds cache\jars). */
public final class JrDirs {
    private JrDirs() {}

    /** %USERPROFILE%\.jr\<sub>, created if missing; null without a user profile. */
    static String of(String sub) {
        var home = Cstr.readEnv("USERPROFILE");
        if (home == null || home.isEmpty()) {
            return null;
        }
        var dir = home + "\\.jr\\" + sub;
        Dirs.mkdirRecursive(dir);
        return dir;
    }
}
