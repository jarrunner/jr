package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

import static jarrunner.jr.N.*;

/** PRP-31: runs Launch.onJvmExit when the process exits while an in-process JVM is running. Registered with
 *  every C runtime jli.dll may call exit() through: the Universal CRT (JDK 9+) and msvcr100/msvcr120 (JDK 8). */
public final class ExitHook {
    private ExitHook() {}

    static boolean armed;

    @Unsafe("turns ExitHook.onExit into a C function pointer; AtExitFn declares its void(void) signature")
    static void install() {
        var handler = (Address) (Object) Function.get(AtExitFn.class, ExitHook.class, "onExit");
        register("ucrtbase.dll", "_crt_atexit", handler);
        register("msvcr120.dll", "atexit", handler);
        register("msvcr100.dll", "atexit", handler);
        armed = true;
    }

    @Unsafe("trusts that the CRT export fn is atexit-shaped: int (*)(void (*)(void))")
    private static void register(String dll, String fn, @Escapes Address handler) {
        var module = WinApi.getModuleHandleW(dll);
        var addr = module.toLong() == 0 ? NULL : WinApi.getProcAddress(module, ascii(fn));
        if (addr.toLong() != 0) {
            var ok = ((AtExitRegisterFn) (Object) addr).invoke(handler) == 0;
            Log.info("Exit hook in " + dll + ": " + (ok ? "registered" : "failed"));
        }
    }

    static void onExit() {
        if (armed) {
            armed = false;
            Launch.onJvmExit();
        }
    }
}
