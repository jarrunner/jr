package jarrunner.jr;

import java.lang.annotation.*;

/** On a method handed to C as a callback that may run on another OS thread. TeaVM's runtime is single-threaded (one
 *  GC shadow stack, no locks), so the reason must say why the main thread runs no Java meanwhile. Inside it: no
 *  memScoped and nothing that suspends a fiber. Checked by teavm_native_check (NC9, PRP-35 phase 6). */
@Retention(RetentionPolicy.SOURCE)
public @interface ForeignThread {
    String value();
}
