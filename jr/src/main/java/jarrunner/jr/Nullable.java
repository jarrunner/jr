package jarrunner.jr;

import java.lang.annotation.*;

/** On a pointer parameter: the method accepts a failure value (NULL, INVALID_HANDLE_VALUE) and handles it itself, so
 *  callers may pass a result they have not tested yet. Checked by teavm_native_check (NC10, PRP-35 phase 8). */
@Retention(RetentionPolicy.SOURCE)
public @interface Nullable {}
