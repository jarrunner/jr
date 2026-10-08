package jarrunner.jr;

import java.lang.annotation.*;

/** On a parameter: the method takes over an open OS resource and closes it on every path with one of the named calls
 *  (Java method names, comma separated). The caller's obligation ends with the call. Checked at compile time by
 *  teavm_native_check (NC8, PRP-35 phase 5). */
@Retention(RetentionPolicy.SOURCE)
public @interface Owns {
    String value();
}
