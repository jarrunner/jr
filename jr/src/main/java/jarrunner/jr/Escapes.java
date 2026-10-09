package jarrunner.jr;

import java.lang.annotation.*;

/** On a pointer (Address or Buf) parameter: the method keeps it after returning (stores it, or hands it to code that
 *  does), so the caller must own memory that outlives the call. Without it a pointer parameter is borrowed: used during
 *  the call only. Checked at compile time by teavm-native-check (NC7, PRP-35 phase 4). */
@Retention(RetentionPolicy.SOURCE)
public @interface Escapes {}
