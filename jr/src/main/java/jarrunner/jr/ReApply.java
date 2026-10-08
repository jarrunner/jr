package jarrunner.jr;

import static jarrunner.jr.N.*;

/**
 * Apply every queued resource change in one update - mirrors resedit.c's reApplyResources. A file
 * with no resource section yet loads fine; FindResource just finds nothing. The target is loaded
 * as a data file, queried, and released before BeginUpdateResourceW opens it for writing -
 * EndUpdateResource cannot rewrite a file that is still mapped. Everything native lives in one memScoped around the
 * whole update: the entries are Java arrays, copied in only for UpdateResourceW, and freed after EndUpdateResource.
 */
public final class ReApply {
    private ReApply() {}

    public static void run(String targetPath, ReStamp s, StringBuilder report) throws ReError {
        memScoped(() -> apply(targetPath, s, report));
    }

    private static void apply(String targetPath, ReStamp s, StringBuilder report) throws ReError {
        var targetW = wcstr(targetPath);
        var module = WinApi.loadLibraryExW(targetW, NULL,
                WinApi.LOAD_LIBRARY_AS_DATAFILE | WinApi.LOAD_LIBRARY_AS_IMAGE_RESOURCE);
        var list = new ReEntries();
        try {
            if (!s.icon.isEmpty()) {
                ReIcon.queue(module, list, s.icon, report);
            }
            if (!s.fileVersion.isEmpty() || !s.productVersion.isEmpty() || !s.versionStrings.isEmpty()) {
                ReVersionInfo.queue(module, list, s, report);
            }
            if (!s.manifest.isEmpty() || !s.executionLevel.isEmpty()) {
                ReManifest.queue(module, list, s, report);
            }
            if (!s.strings.isEmpty()) {
                ReStrings.queue(module, list, s, report);
            }
            for (var raw : s.raws) {
                ReRawResource.queue(module, list, raw, report);
            }
        } finally {
            if (module.toLong() != 0) {
                WinApi.freeLibrary(module);
            }
        }

        var h = WinApi.beginUpdateResourceW(targetW, 0);
        if (h.toLong() == 0) {
            throw new ReError("Cannot open the exe for resource update (error " + WinApi.getLastError()
                    + ") - is it running?");
        }
        for (var e : list.entries()) {
            var size = e.data() != null ? e.data().length : e.file() != null ? (int) FileInfo.size(e.file()) : 0;
            var data = e.data() != null ? alloc(e.data()) : NULL;
            if (e.file() != null && (size <= 0 || !FileIo.readFully(e.file(), data = alloc(size), size))) {
                WinApi.endUpdateResourceW(h, 1); // discard
                throw new ReError("Cannot read resource file: " + e.file());
            }
            var ok = WinApi.updateResourceW(h, e.type().toAddress(), e.name().toAddress(), e.lang(), data, size) != 0;
            // A delete may name something an earlier delete already removed (two icon groups sharing
            // an image), so only a failed WRITE (non-delete) is an error.
            if (!ok && !e.isDelete()) {
                var error = WinApi.getLastError();
                WinApi.endUpdateResourceW(h, 1); // discard
                throw new ReError("Resource update failed (error " + error + ")");
            }
        }
        if (WinApi.endUpdateResourceW(h, 0) == 0) {
            throw new ReError("Writing the resources failed (error " + WinApi.getLastError() + ")");
        }
    }
}
