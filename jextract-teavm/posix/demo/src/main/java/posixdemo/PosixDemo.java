package posixdemo;

import org.teavm.interop.Address;
import org.teavm.interop.Function;
import posix.PosixStructs.*;

import static posix.Posix.*;
import static posixdemo.Cstr.*;

/**
 * One source, every POSIX target: all it knows of the platform is the generated posix.* package of whichever
 * target root it is built with - flags, struct layouts and widths are that platform's own. Exit code = number
 * of failed checks, so a run on the real OS answers "do the generated bindings work" without reading output.
 */
public class PosixDemo {
    static int failures;

    public static void main(String[] args) {
        var exitTest = z("DEMO_EXIT_TEST");
        if (getenv(at(exitTest)).toLong() != 0) exit(42);
        var home = z("HOME");
        System.out.println("HOME = " + read(getenv(at(home))));

        var path = z("/tmp/jextract-teavm-demo.txt");
        var text = z("hello posix");
        var fd = open3(at(path), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        var wrote = write(fd, at(text), 11);
        close(fd);
        check("open3/write/close (variadic open, O_* of this platform)", fd >= 0 && wrote == 11);

        var st = new byte[stat_t.SIZE];
        var statRc = stat(at(path), at(st));
        check("stat + st_size accessor", statRc == 0 && stat_t.st_size(at(st)) == 11);
        check("S_ISREG/S_ISDIR(st_mode), function-like macros", s_isreg(stat_t.st_mode(at(st))) != 0 && s_isdir(stat_t.st_mode(at(st))) == 0);
        check("access(F_OK)", access(at(path), F_OK) == 0);
        var unlinkRc = unlink(at(path));
        check("unlink", unlinkRc == 0 && access(at(path), F_OK) != 0);

        var ts = new byte[timespec.SIZE];
        var clockRc = clock_gettime(CLOCK_MONOTONIC, at(ts));
        check("clock_gettime(CLOCK_MONOTONIC) + timespec accessors",
                clockRc == 0 && timespec.tv_sec(at(ts)) >= 0 && timespec.tv_nsec(at(ts)) < 1_000_000_000L);

        var cwd = new byte[PATH_MAX];
        check("getcwd(PATH_MAX)", getcwd(at(cwd), PATH_MAX).toLong() != 0);

        check("opendir/readdir/closedir + d_name offset (found /tmp)", rootHas("tmp"));
        check("posix_spawn + waitpid, WIFEXITED/WEXITSTATUS", spawnExitCode("exit 7") == 7);
        check("qsort -> comparator callback into Java, qsort(4)", sortsInts());
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        exit(failures);
    }

    static boolean rootHas(String name) {
        var root = z("/");
        var dir = opendir(at(root));
        var found = false;
        for (var e = readdir(dir); e.toLong() != 0; e = readdir(dir)) found |= name.equals(read(e.add(dirent.d_name)));
        closedir(dir);
        return found;
    }

    static int spawnExitCode(String script) {
        byte[] sh = z("/bin/sh"), dashC = z("-c"), body = z(script);
        byte[] argv = new byte[4 * 8], envp = new byte[8], pid = new byte[8], status = new byte[8];
        at(argv).putAddress(at(sh));
        at(argv).add(8).putAddress(at(dashC));
        at(argv).add(16).putAddress(at(body));
        if (posix_spawn(at(pid), at(sh), Address.fromLong(0), Address.fromLong(0), at(argv), at(envp)) != 0) return -1;
        if (waitpid(at(pid).getInt(), at(status), 0) < 0) return -2;
        var s = at(status).getInt();
        return wifexited(s) != 0 ? wexitstatus(s) : -3;
    }

    static boolean sortsInts() {
        int[] in = {5, -3, 9, 0, 7, -3, 100, 1};
        var buf = new byte[in.length * 4];
        for (var i = 0; i < in.length; i++) at(buf).add(i * 4).putInt(in[i]);
        qsort(at(buf), in.length, 4, (Address) (Object) Function.get(QsortCompar.class, PosixDemo.class, "compareInts"));
        for (var i = 1; i < in.length; i++) if (at(buf).add(i * 4 - 4).getInt() > at(buf).add(i * 4).getInt()) return false;
        return at(buf).getInt() == -3 && at(buf).add(28).getInt() == 100;
    }

    static int compareInts(Address a, Address b) {
        return Integer.compare(a.getInt(), b.getInt());
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
