package jarrunner.jr;

import static jarrunner.jr.N.*;

/** The machine's own architecture as Foojay spells it, mirroring javainstall.c's jiNativeArch - see
 *  prp/17-prp-native_machine_architecture_for_jdk_auto-install.md. Always the NATIVE one, even when this
 *  exe is x64 running under emulation on ARM64: the same choice jbang makes for the cache we share with it
 *  (a foreign-architecture jvm.dll fails to load, and Jr already falls back to java.exe mode). */
public final class NativeArch {
    private NativeArch() {}

    public static String foojayName() {
        // Test-only override, not in --help: exercises the ARM64 path on an x64 machine.
        var forced = Cstr.readEnv("JR_TEST_NATIVE_ARCH");
        if ("aarch64".equals(forced) || "x64".equals(forced)) {
            return forced;
        }
        // Looked up at run time rather than imported: IsWow64Process2 exists only on Windows 10 1709+, and a
        // direct import would stop jr loading on anything older. Without it the machine is x64, since every
        // ARM64 Windows has it.
        var kernel32 = WinApi.getModuleHandleA(cstr("kernel32.dll"));
        if (kernel32.toLong() == 0) {
            return "x64";
        }
        var fnAddr = WinApi.getProcAddress(kernel32, cstr("IsWow64Process2"));
        if (fnAddr.toLong() == 0) {
            return "x64";
        }
        var isWow64Process2 = (IsWow64Process2Fn) (Object) fnAddr;
        var machines = alloc(4); // USHORT processMachine at 0, USHORT nativeMachine at 2
        var ok = isWow64Process2.invoke(WinApi.getCurrentProcess(), machines, machines.add(2)) != 0;
        var nativeMachine = machines.add(2).getShort() & 0xFFFF;
        return ok && nativeMachine == WinApi.IMAGE_FILE_MACHINE_ARM64 ? "aarch64" : "x64";
    }
}
