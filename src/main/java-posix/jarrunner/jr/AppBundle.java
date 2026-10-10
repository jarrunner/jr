package jarrunner.jr;

import java.util.ArrayList;
import java.util.List;

import static jarrunner.jr.N.*;

/**
 * One macOS binary that is both a command and an app (PRP-42). jr-maven-plugin puts the app's bundle files (Info.plist,
 * PkgInfo, the icon) into the binary's __DATA,__jrc section after the config, so the binary carries everything an .app
 * needs and the .app is only ever written from it:
 * <ul>
 * <li>{@code -Xjr:install} writes ~/Applications/&lt;Name&gt;.app from those files, copies this binary into its
 *     Contents/MacOS, and puts a symlink to it in ~/.local/bin, so the same file runs from the Dock, Finder and
 *     Launchpad and as a command. jr resolves its own path through the symlink, so either way it knows it is in the
 *     app.</li>
 * <li>On every launch from inside an .app, {@link #refresh} compares the embedded Info.plist with the bundle's. After
 *     -Xjr:update put a new binary in Contents/MacOS they differ, and the bundle files are rewritten from the binary.
 *     A sealed bundle (Contents/_CodeSignature, built and sealed on a Mac) is never touched: that would break its
 *     seal; it is updated as a whole from the release's app zip instead (SelfUpdate).</li>
 * </ul>
 * The section's layout after the config: the config's NUL, zeros up to the next 16-byte boundary, the 8 bytes
 * {@code jrapp1\0\0}, the bundle's folder name as u32 length + UTF-8, then entries of u32 path length + UTF-8 path
 * (relative to the bundle) + u32 data length + data, ending with a path length of 0. Integers are little-endian.
 */
final class AppBundle {
    private AppBundle() {}

    private static final String LSREGISTER =
            "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister";

    /** The bundle's folder name ("My App.app") and its files, as read from this binary. */
    static final class Files {
        String bundleName;
        final List<String> paths = new ArrayList<>();
        final List<Buf> data = new ArrayList<>();
    }

    /** This binary's bundle files, or null when it carries none (no config, or a plugin before PRP-42). */
    static Files embedded() {
        var slot = Os.embeddedSlot();
        if (slot == null) return null;
        var at = 0;
        while (at < slot.size() && slot.getByte(at) != 0) at++;
        at = (at + 1 + 15) & ~15;
        if (at + 12 > slot.size() || !"jrapp1".equals(string(slot.slice(at, 8).ptr(), 8))) return null;
        at += 8;
        var f = new Files();
        var n = length(slot, at);
        if (n <= 0) return null;
        f.bundleName = text(slot.slice(at + 4, n).ptr(), n);
        at += 4 + n;
        while ((n = length(slot, at)) > 0) {
            f.paths.add(text(slot.slice(at + 4, n).ptr(), n));
            at += 4 + n;
            var len = length(slot, at);
            if (len < 0) return null;
            f.data.add(slot.slice(at + 4, len));
            at += 4 + len;
        }
        return n == 0 && f.bundleName.endsWith(".app") && !f.paths.isEmpty() ? f : null;
    }

    /** The u32 length at at, or -1 when it or the bytes it counts would run past the section. */
    private static int length(Buf slot, int at) {
        if (at < 0 || at + 4 > slot.size()) return -1;
        var n = slot.getInt(at);
        return n < 0 || n > slot.size() - at - 4 ? -1 : n;
    }

    /** -Xjr:install. */
    static int install(Config c) {
        var f = Os.APP_BUNDLES ? embedded() : null;
        if (f == null || c.appName.isEmpty()) {
            return report(true, Os.APP_BUNDLES ? "This binary carries no app to install. jr-maven-plugin adds one to a macOS build."
                    : "-Xjr:install makes a macOS app; there is nothing to install on this system.");
        }
        var home = Cstr.readEnv("HOME");
        if (home == null || home.isEmpty()) return report(true, "HOME is not set, so there is nowhere to install.");
        var exe = ExeInfo.fullPath();
        var bundle = home + "/Applications/" + f.bundleName;
        var inside = bundle + "/Contents/MacOS/" + c.appName;
        if (!write(bundle, f)) return report(true, "Could not write " + bundle + ".");
        if (!exe.equals(inside) && !copy(exe, inside)) return report(true, "Could not copy this binary into " + bundle + ".");
        var lsregister = ProcessLauncher.launch(LSREGISTER, args("-f", bundle)); // so Launchpad and Spotlight see it now
        if (!lsregister.started) Log.info("lsregister not run");

        var bin = home + "/.local/bin";
        var link = bin + "/" + c.appName;
        String linkNote;
        if (isLink(link) || !FileIo.exists(link) || exe.equals(realPath(link))) {
            mkdirs(bin);
            PosixApi.unlink(link + ".jr-new");
            var made = PosixApi.symlink(inside, link + ".jr-new") == 0 && PosixApi.rename(link + ".jr-new", link) == 0;
            linkNote = made ? "\nThe command " + c.appName + " is " + link + ", a link to the app." : "\nCould not create " + link + ".";
            var path = Cstr.readEnv("PATH");
            if (made && (path == null || !(":" + path + ":").contains(":" + bin + ":"))) {
                linkNote += "\n" + bin + " is not on your PATH yet: add it in your shell profile.";
            }
        } else {
            linkNote = "\n" + link + " is another file, so no command link was made.";
        }
        return report(false, "Installed " + f.bundleName + " in " + home + "/Applications." + linkNote);
    }

    /** Called on every launch: rewrites the bundle files of the .app this binary runs in when they are not the ones
     *  it carries (after an update). Cheap when nothing changed: one small file read. */
    static void refresh() {
        if (!Os.APP_BUNDLES) return;
        var exe = ExeInfo.fullPath();
        var at = exe.lastIndexOf(".app/Contents/MacOS/");
        if (at < 0) return;
        var bundle = exe.substring(0, at + 4);
        if (FileIo.exists(bundle + "/Contents/_CodeSignature")) return;
        var f = embedded();
        if (f == null) return;
        var i = f.paths.indexOf("Contents/Info.plist");
        if (i < 0) return;
        var want = f.data.get(i);
        var have = FileIo.readAll(bundle + "/Contents/Info.plist");
        if (have != null && have.equals(text(want.ptr(), want.size()))) return;
        Log.info("app bundle: rewriting " + bundle + " from this binary");
        if (write(bundle, f)) ProcessLauncher.launch(LSREGISTER, args("-f", bundle));
    }

    /** Writes each file beside its place and renames it in, so a reader never sees half a file. */
    private static boolean write(String bundle, Files f) {
        for (var i = 0; i < f.paths.size(); i++) {
            var path = bundle + "/" + f.paths.get(i);
            mkdirs(Paths.dirOf(path));
            var d = f.data.get(i);
            if (!writeBytes(path + ".jr-new", d) || PosixApi.rename(path + ".jr-new", path) != 0) {
                PosixApi.unlink(path + ".jr-new");
                return false;
            }
        }
        return true;
    }

    private static boolean writeBytes(String path, Buf d) {
        var out = PosixApi.fopen(path, "wb");
        if (out.toLong() == 0) return false;
        var ok = d.size() == 0 || PosixApi.fwrite(d.ptr(), 1, d.size(), out) == d.size();
        PosixApi.fclose(out);
        return ok;
    }

    /** Copies from to a new file beside to, makes it executable, and renames it over to (a new inode: see SelfUpdate). */
    private static boolean copy(String from, String to) {
        var tmp = to + ".jr-new";
        mkdirs(Paths.dirOf(to));
        var ok = memScoped(() -> {
            var in = PosixApi.fopen(from, "rb");
            if (in.toLong() == 0) return false;
            var out = PosixApi.fopen(tmp, "wb");
            if (out.toLong() == 0) {
                PosixApi.fclose(in);
                return false;
            }
            var buf = Buf.alloc(64 * 1024);
            long n;
            var good = true;
            while (good && (n = PosixApi.fread(buf.ptr(), 1, buf.size(), in)) > 0) {
                good = PosixApi.fwrite(buf.ptr(), 1, n, out) == n;
            }
            PosixApi.fclose(in);
            PosixApi.fclose(out);
            return good;
        });
        if (!ok || PosixApi.chmod(tmp, (short) 0755) != 0 || PosixApi.rename(tmp, to) != 0) {
            PosixApi.unlink(tmp);
            return false;
        }
        return true;
    }

    private static boolean isLink(String path) {
        return PosixApi.readlink(path, alloc(8), 8) >= 0;
    }

    private static String realPath(String path) {
        var p = PosixApi.realpath(utf8(path), alloc(PosixApi.PATH_MAX));
        return p.toLong() == 0 ? "" : string(p);
    }

    private static void mkdirs(String dir) {
        for (var i = 1; i <= dir.length(); i++) {
            if (i == dir.length() || dir.charAt(i) == '/') {
                PosixApi.mkdir(dir.substring(0, i), (short) 0755); // mode_t: 16-bit on macOS, 32 on Linux
            }
        }
    }

    private static List<String> args(String a, String b) {
        var out = new ArrayList<String>();
        out.add(a);
        out.add(b);
        return out;
    }

    private static int report(boolean error, String message) {
        if (error) {
            Ui.error(true, "Install", message);
            return 1;
        }
        Ui.info(true, "Install", message);
        return 0;
    }
}
