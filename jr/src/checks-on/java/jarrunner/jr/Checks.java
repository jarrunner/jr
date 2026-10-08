package jarrunner.jr;

/** Checks build (build-win.ps1 -Checks, dist-checks/): every Buf read and write is checked against its size. ON is a
 *  compile-time constant; src/checks-off holds the release value, false (PRP-35 phase 3). */
final class Checks {
    private Checks() {}

    static final boolean ON = true;
}
