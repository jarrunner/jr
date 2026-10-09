package jarrunner.jr;

/** Outcome of spawning the java process: whether CreateProcessA succeeded, its exit code, and (GUI mode)
 *  whether it was still running when jr stopped waiting for it. */
public class LaunchResult {
    final boolean started;
    final int exitCode;
    boolean detached;

    LaunchResult(boolean started, int exitCode) {
        this.started = started;
        this.exitCode = exitCode;
    }
}
