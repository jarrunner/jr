package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** Function-pointer type for kernel32's IsWow64Process2, obtained at runtime via GetProcAddress because it
 *  exists only on Windows 10 1709+. Hand-written (the generator does not emit function-pointer types) from
 *  wow64apiset.h: {@code WINBOOL IsWow64Process2(HANDLE hProcess, USHORT *pProcessMachine, USHORT *pNativeMachine)}. */
public abstract class IsWow64Process2Fn extends Function {
    public abstract int invoke(Address process, Address processMachine, Address nativeMachine);
}
