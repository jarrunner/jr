package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** Function-pointer type for ntdll's RtlComputeCrc32, looked up at runtime: it is exported by every
 *  Windows since XP (ARM64 too) but declared in no public header, so @Import cannot bind it.
 *  {@code DWORD RtlComputeCrc32(DWORD dwInitial, const BYTE *pData, INT iLen)}. */
public abstract class RtlComputeCrc32Fn extends Function {
    public abstract int invoke(int initial, Address data, int length);
}
