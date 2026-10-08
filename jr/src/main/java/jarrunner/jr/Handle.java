package jarrunner.jr;

import java.lang.annotation.*;

/** On an Address field: it holds an OS handle (a window, a file, a module, a FILE*), which the OS owns, not a
 *  pointer into memory from {@link N}. Checked at compile time by teavm_native_check (PRP-35); no run-time cost. */
@Retention(RetentionPolicy.SOURCE)
public @interface Handle {}
