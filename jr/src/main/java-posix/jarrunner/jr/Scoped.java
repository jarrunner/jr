package jarrunner.jr;

import java.lang.annotation.*;

/** On a method: it allocates memory that piles up (a large buffer, or once per call in a loop), so it may only be
 *  called inside {@link N#memScoped} or from another @Scoped method. Checked at compile time by teavm_native_check. */
@Retention(RetentionPolicy.SOURCE)
public @interface Scoped {}
