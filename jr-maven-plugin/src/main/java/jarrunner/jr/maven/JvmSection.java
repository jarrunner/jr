package jarrunner.jr.maven;

import datapotter.datahelper.Data;
import java.util.List;

@Data
public final class JvmSection extends JvmSection_A {
    String mode;
    List<String> vmArgs;
    String javaArgs;
}
