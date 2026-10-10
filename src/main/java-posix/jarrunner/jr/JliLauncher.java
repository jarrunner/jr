package jarrunner.jr;

import java.util.List;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** jvm=dll on macOS and Linux (PRP-42): runs the JVM inside this process through the JDK's libjli (JLI_Launch, what
 *  the java command itself calls) instead of starting java as a child. On macOS this is what gives the app its own
 *  Dock tile, bundle id and "open with" events: with a child process they belong to java. libjli does the macOS
 *  thread handling itself (the first thread runs the Cocoa run loop, the JVM gets a new one) and calls main() a
 *  second time to do it; jr-posix.h's main sends that second entry back into JLI_Launch without starting TeaVM
 *  again (jx_jli_start). Off by default until it is proven on a Mac: jvm=dll in the config, or -Xjr:jvm=dll. */
public final class JliLauncher {
    private JliLauncher() {}

    /** Null when in-process could not go ahead (the caller starts java as a child, as before); otherwise the app's
     *  exit code. On macOS it never returns: the JVM's thread ends the process. Must be called outside memScoped,
     *  because libjli keeps argv for the life of the process. */
    @Unsafe("trusts that libjli's JLI_Launch has the signature jx_jli_start calls it with (jr-posix.h)")
    public static Integer tryLaunch(String javaPath, List<String> args) {
        var bin = javaPath.lastIndexOf("/bin/");
        if (bin < 0) {
            Log.warn("jvm=dll: cannot tell the Java home from " + javaPath + "; starting java as a child");
            return null;
        }
        var lib = javaPath.substring(0, bin) + Os.JLI_LIBRARY;
        if (!FileIo.exists(lib)) {
            Log.warn("jvm=dll: no " + lib + "; starting java as a child");
            return null;
        }
        if (Os.LINUX && Cstr.readEnv("LD_LIBRARY_PATH") != null) {
            // libjli on Linux re-executes the running program when LD_LIBRARY_PATH needs changing, and the running
            // program is jr, which would take java's arguments for its own.
            Log.warn("jvm=dll: LD_LIBRARY_PATH is set; starting java as a child");
            return null;
        }
        var handle = PosixApi.dlopen(lib, PosixApi.RTLD_NOW);
        if (handle.toLong() == 0) {
            Log.warn("jvm=dll: could not load " + lib + "; starting java as a child");
            return null;
        }
        var launch = PosixApi.dlsym(handle, "JLI_Launch");
        if (launch.toLong() == 0) {
            Log.warn("jvm=dll: " + lib + " has no JLI_Launch; starting java as a child");
            return null;
        }
        var argc = args.size() + 1;
        var p = Address.sizeOf();
        var argv = Buf.alloc((argc + 1) * p);
        argv.putAddress(0, utf8(javaPath));
        for (var i = 0; i < args.size(); i++) {
            argv.putAddress((i + 1) * p, utf8(args.get(i)));
        }
        argv.putAddress(argc * p, NULL);
        Log.info("Invoking in-process JVM via " + lib + " (" + argc + " args)");
        return PosixApi.jliStart(launch, argc, argv.ptr());
    }
}
