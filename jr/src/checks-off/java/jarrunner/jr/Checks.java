package jarrunner.jr;

/** Release build: no run-time checks on native buffers. ON is a compile-time constant, so javac drops every
 *  {@code if (Checks.ON ...)} block and the exe carries none of it. build-win.ps1 -Checks compiles src/checks-on
 *  instead (PRP-35 phase 3). */
final class Checks {
    private Checks() {}

    static final boolean ON = false;
}
