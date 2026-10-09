package jarrunner.jr;

import java.lang.annotation.*;

/** On a method handed to C as a callback that runs on the calling thread, before the C function returns (an Enum*
 *  callback). The reason names who calls it. Checked by teavm-native-check (NC9, PRP-35 phase 6). */
@Retention(RetentionPolicy.SOURCE)
public @interface SameThread {
    String value();
}
