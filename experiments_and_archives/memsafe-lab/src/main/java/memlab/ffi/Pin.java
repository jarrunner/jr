package memlab.ffi;

import org.teavm.interop.Address;

public final class Pin {
    private Pin() {}

    public interface AddrBody { long run(Address a); }

    public static long with(byte[] buf, AddrBody body) {
        var r = body.run(Address.ofData(buf));
        Address.pin(buf);
        return r;
    }

    public static long withNoPin(byte[] buf, AddrBody body) {
        return body.run(Address.ofData(buf));
    }

    public static long withLength(byte[] buf, AddrBody body) {
        var r = body.run(Address.ofData(buf));
        return buf.length < 0 ? -1 : r;
    }
}
