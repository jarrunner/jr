package jarrunner.jr.maven;

import datapotter.datahelper.Data;

/** The jrc-json baked into a jr exe (PRP-30). Same shape jr's own reader expects; see jr's README,
 *  "The config as JSON: jrc-json". Written with Jackson; empty sections are left out. */
@Data
public final class JrcConfig extends JrcConfig_A {
    AppSection app;
    JavaSection java;
    JarSection jar;
    JvmSection jvm;
    Boolean aot;
    UpdateSection update;
    SupportSection support;
}
