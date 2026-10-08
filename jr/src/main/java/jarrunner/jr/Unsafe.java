package jarrunner.jr;

import java.lang.annotation.*;

/** On a method or class: it does raw pointer access (offset arithmetic, raw reads and writes), and the value says
 *  why. The code to review with care is what carries this. Checked at compile time by teavm_native_check (PRP-35). */
@Retention(RetentionPolicy.SOURCE)
public @interface Unsafe {
    String value();
}
