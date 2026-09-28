package memlab;

import org.teavm.interop.Address;
import org.teavm.interop.Import;

public final class C {
    private C() {}

    @Import(name = "malloc") public static native Address malloc(long n);
    @Import(name = "free") public static native void free(Address p);
    @Import(name = "memset") public static native Address memset(Address p, int v, long n);
    @Import(name = "strcmp") public static native int strcmp(Address a, Address b);
    @Import(name = "strlen") public static native long strlen(Address a);
    @Import(name = "lstrlenW") public static native int lstrlenW(Address a);
    @Import(name = "SetEnvironmentVariableW") public static native int setEnvironmentVariableW(Address name, Address value);
    @Import(name = "GetEnvironmentVariableW") public static native int getEnvironmentVariableW(Address name, Address buf, int size);
    @Import(name = "GetFileAttributesExA") public static native int getFileAttributesExA(Address path, int level, Address data);
    @Import(name = "QueryPerformanceCounter") public static native int queryPerformanceCounter(Address out);
    @Import(name = "QueryPerformanceFrequency") public static native int queryPerformanceFrequency(Address out);
}
