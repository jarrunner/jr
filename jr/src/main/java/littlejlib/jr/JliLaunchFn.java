package littlejlib.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** Function-pointer type for jli.dll's JLI_Launch, obtained at runtime via GetProcAddress. */
public abstract class JliLaunchFn extends Function {
    public abstract int invoke(int argc, Address argv, int jargc, Address jargv,
            int appClassC, Address appClassV, Address fullVersion, Address dotVersion,
            Address pname, Address lname, byte javaArgs, byte cpWildcard, byte javaw, int ergo);
}
