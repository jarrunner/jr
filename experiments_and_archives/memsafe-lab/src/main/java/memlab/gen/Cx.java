package memlab.gen;

import memlab.C;
import memlab.ffi.F;
import org.teavm.interop.Address;

import static memlab.ffi.HeapCheck.arg;

public final class Cx {
    private Cx() {}

    public static int strcmp(String a, String b) {
        var mk = F.mark();
        var r = C.strcmp(F.c(a), F.c(b));
        F.reset(mk);
        return r;
    }

    public static long strlen(String a) {
        var mk = F.mark();
        var r = C.strlen(F.c(a));
        F.reset(mk);
        return r;
    }

    public static int setEnvironmentVariableW(String name, String value) {
        var mk = F.mark();
        var r = C.setEnvironmentVariableW(F.w(name), F.w(value));
        F.reset(mk);
        return r;
    }

    public static int strcmp(Address a, Address b) {
        return C.strcmp(arg(a, "strcmp", 0), arg(b, "strcmp", 1));
    }

    public static int getFileAttributesExA(Address path, int level, Address data) {
        return C.getFileAttributesExA(arg(path, "GetFileAttributesExA", 0), level, arg(data, "GetFileAttributesExA", 2));
    }
}
