package littlejlib.jr;

import java.util.ArrayList;
import java.util.List;

import static littlejlib.jr.N.*;

/** Directory helpers for the AOT cache - mirrors the Windows Dirs' FindFirstFile-based ones with
 *  opendir/readdir/closedir. The JDK-auto-install directory tree helpers (mkdirRecursive,
 *  removeDirTree, findSingleSubdir, moveDirectory) are not needed by PosixJr v1 (auto-install is
 *  out of scope for this pass - see PRP-21's status file) and are left out rather than ported
 *  speculatively. */
public final class Dirs {
    private Dirs() {}

    /** Directory entry names directly under parentDir (files excluded, "." and ".." excluded). */
    public static List<String> listDirNames(String parentDir) {
        var out = new ArrayList<String>();
        var dir = PosixApi.opendir(cstr(parentDir));
        if (dir.toLong() == 0) {
            return out;
        }
        for (var e = PosixApi.readdir(dir); e.toLong() != 0; e = PosixApi.readdir(dir)) {
            var type = PosixOffsets.dirent.d_type(e) & 0xFF;
            if (type != PosixApi.DT_DIR) {
                continue;
            }
            var name = string(e.add(PosixOffsets.dirent.d_name), 256);
            if (!name.equals(".") && !name.equals("..")) {
                out.add(name);
            }
        }
        PosixApi.closedir(dir);
        return out;
    }
}
