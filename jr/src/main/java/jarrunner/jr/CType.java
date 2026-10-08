package jarrunner.jr;

import java.lang.annotation.*;

/** The C type an Address points to, e.g. {@code @CType("struct _STARTUPINFOW")}: written by jextract_teavm on the
 *  generated bindings (parameters, returns, struct classes, accessors), checked at compile time by
 *  teavm_native_check (rule NC6). No run-time cost. */
@Retention(RetentionPolicy.SOURCE)
public @interface CType {
    String value();
}
