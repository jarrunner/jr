package jarrunner.jr;

import org.teavm.interop.Function;

/** A {@code void (*)(void)} handler for the C runtime's exit list (PRP-31): jli.dll calls exit(1) itself
 *  when the JVM fails to start, so in-process mode this is the only point at which jr runs again. */
public abstract class AtExitFn extends Function {
    public abstract void invoke();
}
