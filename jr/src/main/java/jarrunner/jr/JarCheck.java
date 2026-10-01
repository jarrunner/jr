package jarrunner.jr;

/** Per-run check that a downloaded jar is still the one verified by SHA-256 at download (PRP-30).
 *  run.verify / jar.verify: "crc32" (default) reads the whole jar for its CRC32 (~40 ms for 80 MB),
 *  "sha256" re-hashes it (~120 ms for 80 MB, cannot be forged), "none" reads nothing and trusts the
 *  download check plus the size kept in the sidecar. The expected CRC32 comes from the exe's own
 *  config (run.crc32 / jar.crc32, baked in by the maven plugin, so nobody can change it without
 *  changing the exe); only an exe built without one falls back to the sidecar's. */
public final class JarCheck {
    private JarCheck() {}

    /** Null when the jar may run, else the message to show. */
    static String changedSinceVerified(Config c, String jar, String sha) {
        var mode = c.runVerify.isEmpty() ? "crc32" : c.runVerify;
        if (mode.equals("none")) {
            return null;
        }
        if (mode.equals("sha256")) {
            var actual = Sha256.ofFile(jar);
            return actual != null && AsciiStr.equalsIgnoreCase(actual, sha) ? null : changed(jar, "SHA-256");
        }
        var expected = !c.runCrc32.isEmpty() ? c.runCrc32 : RemoteJar.sidecarCrc(jar);
        if (expected == null) {
            // A sidecar from before PRP-30 has no CRC32: verify fully once, and record one.
            var actual = Sha256.ofFile(jar);
            if (actual == null || !AsciiStr.equalsIgnoreCase(actual, sha)) return changed(jar, "SHA-256");
            RemoteJar.reverify(jar, sha);
            return null;
        }
        var actual = Crc32.ofFile(jar);
        return actual != null && AsciiStr.equalsIgnoreCase(actual, expected) ? null : changed(jar, "CRC32");
    }

    private static String changed(String jar, String what) {
        return "The application's jar has changed since jr verified it (" + what + " mismatch), so it was not run:\n"
                + jar + "\n\nDelete it and jr will download it again.";
    }
}
