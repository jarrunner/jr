package littlejlib.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** Function-pointer type for jli.dll's JLI_CmdToArgs. */
public abstract class JliCmdToArgsFn extends Function {
    public abstract void invoke(Address cmdLine);
}
