package littlejlib.jr;

/** Outcome of spawning the java process: whether CreateProcessA succeeded, and its exit code. */
public class LaunchResult {
    final boolean started;
    final int exitCode;

    LaunchResult(boolean started, int exitCode) {
        this.started = started;
        this.exitCode = exitCode;
    }
}
