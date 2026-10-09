package jarrunner.jr;

import java.lang.annotation.*;

/** On a pointer parameter: the method may return it, or a pointer into it (a field, an offset), but keeps nothing.
 *  At the call site the result counts as part of what was passed. Checked by teavm-native-check (NC7, PRP-35). */
@Retention(RetentionPolicy.SOURCE)
public @interface Returned {}
