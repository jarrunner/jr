package jarrunner.jr.maven;

import datapotter.datahelper.Data;

/** Exactly one of maven, url or path. */
@Data
public final class JarSource extends JarSource_A {
    String maven;
    String url;
    String path;
}
