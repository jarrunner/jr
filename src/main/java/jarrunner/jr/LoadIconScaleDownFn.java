package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** comctl32's {@code HRESULT LoadIconWithScaleDown(HINSTANCE, PCWSTR, int cx, int cy, HICON *)}, looked up at run time:
 *  it exists only in Common Controls 6, so importing it would stop an exe without jr's manifest from starting at all
 *  (STATUS_ENTRYPOINT_NOT_FOUND, seen 2026-10-03). */
public abstract class LoadIconScaleDownFn extends Function {
    public abstract int invoke(Address hinst, Address name, int cx, int cy, Address icon);
}
