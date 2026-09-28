package memlab.gallery;

import memlab.ffi.Ffi;
import org.teavm.interop.Address;
import pocapp.jr.WinApi;

public final class Win {
    private Win() {}

    static final Address NULL = Address.fromInt(0);

    static String env(String name) {
        return Ffi.s(m -> {
            var buf = m.buf(2048);
            return memlab.C.getEnvironmentVariableW(m.w(name), buf, 1024) == 0 ? null : m.wstr(buf);
        });
    }

    static void setEnv(String name, String value) {
        Ffi.run(m -> WinApi.setEnvironmentVariableA(m.c(name), m.c(value)));
    }

    static boolean invalid(org.teavm.interop.Address h) {
        return h.toLong() == 0 || h.toLong() == WinApi.INVALID_HANDLE_VALUE.toLong();
    }
}
