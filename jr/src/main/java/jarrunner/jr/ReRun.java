package jarrunner.jr;

import static jarrunner.jr.N.*;

/** Entry point for a resource-editing/signing action - mirrors resedit.c's reRun. Order is always:
 *  copy, strip any old signature, resources, sign - signing last, because any later change to the
 *  file would invalidate the signature. */
public final class ReRun {
    private ReRun() {}

    public record Result(boolean ok, String report) {}

    public static Result run(ReStamp s) {
        if (!s.listResources.isEmpty()) {
            try {
                return new Result(true, ReList.list(s.listResources));
            } catch (ReError e) {
                return new Result(false, e.getMessage());
            }
        }

        if (!s.make.isEmpty() && !s.edit.isEmpty()) {
            return new Result(false, "Use either -Xjr:make= or -Xjr:edit=, not both");
        }

        var created = false;
        String target = null;
        var report = new StringBuilder();
        try {
            if (!s.make.isEmpty()) {
                var selfFull = fullPathName(ExeInfo.fullPath());
                target = fullPathName(s.make);
                if (selfFull == null || target == null) {
                    throw new ReError("Cannot resolve the output path: " + s.make);
                }
                if (AsciiStr.equalsIgnoreCase(selfFull, target)) {
                    throw new ReError("-Xjr:make cannot overwrite the running exe itself; use -Xjr:edit on a copy");
                }
                if (WinApi.copyFileW(ExeInfo.fullPath(), target, 0) == 0) {
                    throw new ReError("Cannot create " + target + " (error " + WinApi.getLastError() + ")");
                }
                created = true;
                report.append("Created ").append(target).append(" (a copy of ").append(selfFull).append(")\n");
            } else {
                target = fullPathName(s.edit);
                if (target == null || !FileIo.exists(target)) {
                    throw new ReError("No such file: " + s.edit);
                }
                report.append("Editing ").append(target).append("\n");
            }

            if (!s.signTimestamp.isEmpty() && !s.hasSigning()) {
                throw new ReError("-Xjr:sign.timestamp needs -Xjr:sign= or -Xjr:sign.thumbprint=");
            }

            if (s.hasEdits() && RePe.stripSignature(target)) {
                report.append("Removed the existing signature (it would no longer be valid)\n");
            }
            if (s.hasResourceEdits()) {
                ReApply.run(target, s, report);
            }
            if (s.hasSigning()) {
                ReSign.sign(target, s, report);
            }
            return new Result(true, report.toString());
        } catch (ReError e) {
            if (created) {
                WinApi.deleteFileW(target); // no half-made output left behind
            }
            return new Result(false, e.getMessage() + (created ? "\n(the new exe was not kept)" : ""));
        }
    }

    private static String fullPathName(String path) {
        var buf = alloc(4096 * 2);
        var len = WinApi.getFullPathNameW(path, 4096, buf, NULL);
        return len == 0 || len >= 4096 ? null : wstring(buf, len);
    }
}
