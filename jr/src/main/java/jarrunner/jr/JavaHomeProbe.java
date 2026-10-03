package jarrunner.jr;

import static jarrunner.jr.N.*;

/** PRP-31: what a Java home's binaries say about themselves, read as data and never by starting them:
 *  the version resource of jvm.dll, and the PE machine type of a dll or exe. */
public final class JavaHomeProbe {
    private JavaHomeProbe() {}

    private static int self = -1, nat = -1;

    /** Major version from jvm.dll's VS_FIXEDFILEINFO (25.0.1.0 -> 25; Java 8's 8.0.4020.8 -> 8), 0 if unreadable. */
    static int dllMajor(String dll) {
        return memScoped(() -> {
            var size = WinApi.getFileVersionInfoSizeA(cstr(dll), NULL);
            if (size <= 0) {
                return 0;
            }
            var data = alloc(size);
            var info = ptrVar();
            var len = intVar();
            if (WinApi.getFileVersionInfoA(cstr(dll), 0, size, data) == 0
                    || WinApi.verQueryValueW(data, wcstr("\\"), info, len) == 0
                    || len.getInt() < WinOffsets.VS_FIXEDFILEINFO.SIZE) {
                return 0;
            }
            var ms = WinOffsets.VS_FIXEDFILEINFO.dwFileVersionMS(info.getAddress());
            var major = ms >>> 16;
            return major == 1 ? ms & 0xFFFF : major;
        });
    }

    /** IMAGE_FILE_MACHINE_* from a PE file's header, 0 if it cannot be read. */
    static int peMachine(String path) {
        return memScoped(() -> {
            var f = WinApi.fopen(cstr(path), cstr("rb"));
            if (f.toLong() == 0) {
                return 0;
            }
            var buf = alloc(4096);
            var n = (int) WinApi.fread(buf, 1, 4096, f);
            WinApi.fclose(f);
            if (n < 64 || WinOffsets.IMAGE_DOS_HEADER.e_magic(buf) != (short) WinApi.IMAGE_DOS_SIGNATURE) {
                return 0;
            }
            var lfanew = WinOffsets.IMAGE_DOS_HEADER.e_lfanew(buf);
            if (lfanew < 0 || lfanew + 6 > n || buf.add(lfanew).getInt() != WinApi.IMAGE_NT_SIGNATURE) {
                return 0;
            }
            return buf.add(lfanew + 4).getShort() & 0xFFFF;
        });
    }

    /** The machine this jr exe was built for: an in-process JVM must match it. */
    static int selfMachine() {
        if (self < 0) {
            self = peMachine(ExeInfo.fullPath());
        }
        return self;
    }

    static int nativeMachine() {
        if (nat < 0) {
            nat = NativeArch.foojayName().equals("aarch64") ? WinApi.IMAGE_FILE_MACHINE_ARM64 : WinApi.IMAGE_FILE_MACHINE_AMD64;
        }
        return nat;
    }

    /** As a child process: ARM64 Windows runs x64 and x86 too, x64 Windows runs x86 but not ARM64. */
    static boolean canRun(int machine) {
        return machine == 0 || machine == nativeMachine() || machine == WinApi.IMAGE_FILE_MACHINE_I386
                || nativeMachine() == WinApi.IMAGE_FILE_MACHINE_ARM64 && machine == WinApi.IMAGE_FILE_MACHINE_AMD64;
    }

    static String machineName(int m) {
        return m == WinApi.IMAGE_FILE_MACHINE_AMD64 ? "x64" : m == WinApi.IMAGE_FILE_MACHINE_ARM64 ? "ARM64"
                : m == WinApi.IMAGE_FILE_MACHINE_I386 ? "x86" : "unknown-architecture";
    }
}
