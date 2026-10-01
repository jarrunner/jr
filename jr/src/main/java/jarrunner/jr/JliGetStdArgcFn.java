package jarrunner.jr;

import org.teavm.interop.Function;

/** Function-pointer type for jli.dll's JLI_GetStdArgc. */
public abstract class JliGetStdArgcFn extends Function {
    public abstract int invoke();
}
