package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

import static jarrunner.jr.N.*;

/** Directory helpers for the AOT cache - mirrors the Windows Dirs' FindFirstFile-based ones with
 *  opendir/readdir/closedir. JavaInstall (PRP-42) moves and removes folders with rename(2) and
 *  /bin/rm instead of porting the Windows tree helpers. */
public final class Dirs {
    private Dirs() {}

    /** Directory entry names directly under parentDir (files excluded, "." and ".." excluded). */
    public static List<String> listDirNames(String parentDir) {
        var out = new ArrayList<String>();
        var dir = PosixApi.opendir(parentDir);
        if (dir.toLong() == 0) {
            return out;
        }
        for (var e = PosixApi.readdir(dir); e.toLong() != 0; e = PosixApi.readdir(dir)) {
            var type = PosixOffsets.dirent.d_type(e) & 0xFF;
            if (type != PosixApi.DT_DIR) {
                continue;
            }
            var name = string(PosixOffsets.dirent.d_name(e), 256);
            if (!name.equals(".") && !name.equals("..")) {
                out.add(name);
            }
        }
        PosixApi.closedir(dir);
        return out;
    }
}
