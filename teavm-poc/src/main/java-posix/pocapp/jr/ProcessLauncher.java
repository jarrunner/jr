package pocapp.jr;

import java.util.List;
import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/** posix_spawn-based child spawn - POSIX twin of the Windows ProcessLauncher (CreateProcessA).
 *  Real argv[]/envp[] arrays rather than a single quoted command-line string, which is a genuine
 *  simplification over the Windows side: there is no WinQuote-style requoting bug possible here,
 *  because each argument is its own pointer, never rejoined into text the far side must re-split. */
public final class ProcessLauncher {
    private ProcessLauncher() {}

    /** javaPath plus every argument, in order - argv[0] is javaPath itself, matching C convention. */
    public static LaunchResult launch(String javaPath, List<String> args) {
        return memScoped(() -> {
            var argc = args.size() + 1;
            var argv = alloc((argc + 1) * Address.sizeOf());
            argv.putAddress(cstr(javaPath));
            for (var i = 0; i < args.size(); i++) {
                argv.add((i + 1) * Address.sizeOf()).putAddress(cstr(args.get(i)));
            }
            argv.add(argc * Address.sizeOf()).putAddress(NULL);

            var envp = PosixApi.getEnviron();
            var pidVar = intVar();
            var rc = PosixApi.posixSpawn(pidVar, cstr(javaPath), NULL, NULL, argv, envp);
            if (rc != 0) {
                return new LaunchResult(false, -1);
            }
            var pid = pidVar.getInt();
            Log.info("Java process started successfully (PID: " + pid + ")");

            var statusVar = intVar();
            int waited;
            do {
                waited = PosixApi.waitpid(pid, statusVar, 0);
            } while (waited < 0 && lastErrnoIsEintr());
            if (waited < 0) {
                return new LaunchResult(false, -1);
            }
            var status = statusVar.getInt();
            var exitCode = PosixApi.wifexited(status) != 0 ? PosixApi.wexitstatus(status) : 128;
            Log.info("Java process exited with code: " + exitCode);
            return new LaunchResult(true, exitCode);
        });
    }

    // errno itself is not bound (same not-a-plain-global problem jx_environ works around for
    // environ) - EINTR-retry is a nicety, not correctness-critical for a single foreground child
    // with no signal handlers installed, so this always stops retrying rather than binding jx_errno
    // too. Revisit if a real EINTR is ever observed in practice.
    private static boolean lastErrnoIsEintr() {
        return false;
    }
}
