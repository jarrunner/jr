package jarrunner.jr;

import static jarrunner.jr.N.*;

/** The machine's real architecture via uname(2) - POSIX twin of the Windows NativeArch
 *  (IsWow64Process2). Much simpler here: POSIX has no "running x64 under ARM64 emulation"
 *  concept the way Windows-on-ARM does - uname(2) always reports the real machine, not a
 *  possibly-emulated one, so there is no equivalent of the Windows side's fallback chain. Used by
 *  JavaInstall (PRP-42) to pick the download. */
public final class NativeArch {
    private NativeArch() {}

    public static String machine() {
        var buf = alloc(PosixOffsets.utsname.SIZE);
        if (PosixApi.uname(buf) != 0) {
            return "unknown";
        }
        return string(PosixOffsets.utsname.machine(buf), 65);
    }
}
