package memlab.gallery;

import memlab.ffi.Ffi;
import pocapp.jr.IsWow64Process2Fn;
import pocapp.jr.WinOffsets.*;

import static memlab.gallery.Chaos.gc;
import static pocapp.jr.WinApi.*;

public final class Sites {
    private Sites() {}

    static long lastWriteTimeRaw(String path) {
        return Ffi.l(m -> {
            var d = m.buf(WIN32_FILE_ATTRIBUTE_DATA.SIZE);
            if (getFileAttributesExA(m.c(path), GET_FILEEX_INFO_STANDARD, d) == 0) return -1;
            gc();
            var ft = d.add(WIN32_FILE_ATTRIBUTE_DATA.ftLastWriteTime);
            return (FILETIME.dwHighDateTime(ft) & 0xFFFFFFFFL) << 32 | FILETIME.dwLowDateTime(ft) & 0xFFFFFFFFL;
        });
    }

    static String findSingleSubdir(String parentDir) {
        return Ffi.s(m -> {
            var fd = m.buf(WIN32_FIND_DATAA.SIZE);
            var h = findFirstFileA(m.c(parentDir + "\\*"), fd);
            if (Win.invalid(h)) return null;
            try {
                do {
                    var name = m.str(fd.add(WIN32_FIND_DATAA.cFileName));
                    gc();
                    if (!name.equals(".") && !name.equals("..")
                            && (WIN32_FIND_DATAA.dwFileAttributes(fd) & FILE_ATTRIBUTE_DIRECTORY) != 0) return name;
                } while (findNextFileA(h, fd) != 0);
                return null;
            } finally {
                findClose(h);
            }
        });
    }

    static int runAndWait(String cmdLine) {
        return Ffi.i(m -> {
            var si = m.buf(STARTUPINFOA.SIZE);
            STARTUPINFOA.cb(si, STARTUPINFOA.SIZE);
            gc();
            var pi = m.buf(PROCESS_INFORMATION.SIZE);
            if (createProcessA(Win.NULL, m.c(cmdLine), Win.NULL, Win.NULL, 0, CREATE_NO_WINDOW,
                    Win.NULL, Win.NULL, si, pi) == 0) return -1;
            gc();
            var hp = PROCESS_INFORMATION.hProcess(pi);
            waitForSingleObject(hp, INFINITE);
            var code = m.int32();
            getExitCodeProcess(hp, code);
            closeHandle(hp);
            closeHandle(PROCESS_INFORMATION.hThread(pi));
            return code.getInt();
        });
    }

    static String nativeArch() {
        return Ffi.s(m -> {
            var fn = getProcAddress(getModuleHandleA(m.c("kernel32.dll")), m.c("IsWow64Process2"));
            if (fn.toLong() == 0) return "x64";
            var machines = m.buf(4);
            gc();
            var ok = ((IsWow64Process2Fn) (Object) fn).invoke(getCurrentProcess(), machines, machines.add(2)) != 0;
            return ok && (machines.add(2).getShort() & 0xFFFF) == IMAGE_FILE_MACHINE_ARM64 ? "aarch64" : "x64";
        });
    }

    static void aotEnv(String cachePath, boolean exists) {
        Ffi.run(m -> {
            setEnvironmentVariableA(m.c("JR_AOT_CACHE"), m.c(cachePath));
            setEnvironmentVariableA(m.c("JR_AOT_STATE"), m.c(exists ? "using" : "creating"));
        });
    }
}
