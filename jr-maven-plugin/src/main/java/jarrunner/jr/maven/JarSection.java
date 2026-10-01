package jarrunner.jr.maven;

import datapotter.datahelper.Data;
import java.util.List;

@Data
public final class JarSection extends JarSection_A {
    String sha256;
    List<JarSource> sources;
}
