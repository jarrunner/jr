package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** ucrtbase's {@code int _crt_atexit(_PVFV)} (or an older CRT's atexit), found at run time: jli.dll's exit()
 *  runs the exit list of the C runtime the JDK was built with, not the one jr links. */
public abstract class AtExitRegisterFn extends Function {
    public abstract int invoke(Address handler);
}
