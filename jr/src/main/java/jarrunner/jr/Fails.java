package jarrunner.jr;

import java.lang.annotation.*;

/** On a method whose result may be its failure value: "NULL", or a constant such as INVALID_HANDLE_VALUE. Callers
 *  must test the result against that value before passing it on (Rust's Option, at compile time and free at run
 *  time). The generated bindings carry it from the symbols file's fails=. Checked by teavm_native_check (NC10,
 *  PRP-35 phase 8). */
@Retention(RetentionPolicy.SOURCE)
public @interface Fails {
    String value();
}
