package jarrunner.jr;

import java.lang.annotation.*;

/** On a method that returns an open OS resource (a handle, a FILE*, a module): the caller owns it and must close it
 *  on every path with one of the named calls (Java method names, comma separated), return it, store it in a field, or
 *  pass it to an @Owns parameter. The generated bindings carry it from the symbols file's releases=; hand-written
 *  methods that return an open resource carry it too. Checked at compile time by teavm_native_check (NC8, PRP-35
 *  phase 5). */
@Retention(RetentionPolicy.SOURCE)
public @interface Acquires {
    String value();
}
