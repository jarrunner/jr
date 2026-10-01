package jarrunner.jr.maven;

import datapotter.datahelper.Data;
import java.util.List;

@Data
public final class JarSection extends JarSection_A {
    String sha256;
    /** Baked in so the per-run check compares against a value inside the exe, not a sidecar file. */
    String crc32;
    /** Per-run check: crc32 (jr's default), sha256 or none. */
    String verify;
    List<JarSource> sources;
}
