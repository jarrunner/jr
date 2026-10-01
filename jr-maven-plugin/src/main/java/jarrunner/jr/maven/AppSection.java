package jarrunner.jr.maven;

import datapotter.datahelper.Data;
import java.util.List;

@Data
public final class AppSection extends AppSection_A {
    String id;
    String name;
    String version;
    List<String> args;
}
