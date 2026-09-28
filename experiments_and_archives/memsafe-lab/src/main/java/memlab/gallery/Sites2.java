package memlab.gallery;

import pocapp.jr.IsWow64Process2Fn;
import pocapp.jr.WinOffsets.*;

import static memlab.gallery.Chaos.gc;
import static memlab.nat.N.*;
import static pocapp.jr.WinApi.*;

public final class Sites2 {
    private Sites2() {}

    static long lastWriteTimeRaw(String path) {
        return memScoped(() -> {
            var data = alloc(WIN32_FILE_ATTRIBUTE_DATA.SIZE);
            if (getFileAttributesExA(cstr(path), GET_FILEEX_INFO_STANDARD, data) == 0) return -1L;
            gc();
            var ft = data.add(WIN32_FILE_ATTRIBUTE_DATA.ftLastWriteTime);
            return (FILETIME.dwHighDateTime(ft) & 0xFFFFFFFFL) << 32 | FILETIME.dwLowDateTime(ft) & 0xFFFFFFFFL;
        });
    }

    static String findSingleSubdir(String parentDir) {
        return memScoped(() -> {
            var fd = alloc(WIN32_FIND_DATAA.SIZE);
            var h = findFirstFileA(cstr(parentDir + "\\*"), fd);
            if (Win.invalid(h)) return null;
            try {
                do {
                    var name = string(fd.add(WIN32_FIND_DATAA.cFileName));
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
        return memScoped(() -> {
            var si = alloc(STARTUPINFOA.SIZE);
            STARTUPINFOA.cb(si, STARTUPINFOA.SIZE);
            var pi = alloc(PROCESS_INFORMATION.SIZE);
            gc();
            if (createProcessA(NULL, cstr(cmdLine), NULL, NULL, 0, CREATE_NO_WINDOW, NULL, NULL, si, pi) == 0) return -1;
            var process = PROCESS_INFORMATION.hProcess(pi);
            waitForSingleObject(process, INFINITE);
            var exitCode = intVar();
            getExitCodeProcess(process, exitCode);
            closeHandle(process);
            closeHandle(PROCESS_INFORMATION.hThread(pi));
            return exitCode.getInt();
        });
    }

    static String nativeArch() {
        return memScoped(() -> {
            var fn = getProcAddress(getModuleHandleA(cstr("kernel32.dll")), cstr("IsWow64Process2"));
            if (fn.toLong() == 0) return "x64";
            var machines = alloc(4);
            gc();
            var ok = ((IsWow64Process2Fn) (Object) fn).invoke(getCurrentProcess(), machines, machines.add(2)) != 0;
            return ok && (machines.add(2).getShort() & 0xFFFF) == IMAGE_FILE_MACHINE_ARM64 ? "aarch64" : "x64";
        });
    }

    static void aotEnv(String cachePath, boolean exists) {
        memScoped(() -> {
            setEnvironmentVariableA(cstr("JR_AOT_CACHE"), cstr(cachePath));
            setEnvironmentVariableA(cstr("JR_AOT_STATE"), cstr(exists ? "using" : "creating"));
        });
    }

    static String argvRoundTrip() {
        return memScoped(() -> {
            var argv = cstrArray("java", "-jar", "app.jar", "two words");
            gc();
            var sb = new StringBuilder();
            for (var i = 0; i < 4; i++) sb.append(string(argv.add(i * 8).getAddress())).append(',');
            return sb.toString();
        });
    }
}
