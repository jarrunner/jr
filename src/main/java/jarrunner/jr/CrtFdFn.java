package jarrunner.jr;

import org.teavm.interop.Function;

/** {@code int _open_osfhandle(intptr_t, int)} or {@code int _dup2(int, int)} of another C runtime, found at run time
 *  (PRP-31): the JDK writes through its own CRT's stderr, which jr's msvcrt redirect does not reach. */
public abstract class CrtFdFn extends Function {
    public abstract int invoke(long a, int b);
}
