package pocapp.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** Function-pointer type for jli.dll's JLI_GetStdArgs - returns a pointer to a JLI_StdArg[] array. */
public abstract class JliGetStdArgsFn extends Function {
    public abstract Address invoke();
}
