package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;
import org.teavm.interop.Unmanaged;

import static jarrunner.jr.N.*;

/** PRP-31: lets jr retry when an in-process JVM fails to start. jli.dll then calls exit(1) itself, on its JavaMain
 *  thread, so a handler on every C runtime jli.dll may exit through (the Universal CRT for JDK 9+, msvcr100/msvcr120
 *  for JDK 8) is the only point at which jr runs again.
 *  PRP-43: TeaVM code may run only on jr's own thread (one shadow stack, one heap, no locks). So the handler is
 *  {@code @Unmanaged}: no shadow-stack frame, no allocation, no exceptions, only Win32 calls and primitive fields
 *  set up beforehand. On the JVM's thread it either lets the exit go ahead (a JVM was created: the app called
 *  System.exit) or ends that thread with ExitThread, which makes JLI_Launch return on jr's thread, where
 *  {@link #handedBack} tells Launch to retry. */
public final class ExitHook {
    private ExitHook() {}

    static boolean armed;
    /** Set by the handler, read by jr's thread after JLI_Launch returns: the JVM did not start. */
    static boolean handedBack;
    private static int jrThread;
    /** Address of a malloc'd block the handler reads, never freed: the vmBuf and nVms out-parameters, then
     *  "jvm.dll" in UTF-16 and "JNI_GetCreatedJavaVMs" in ASCII. Kept as a number because it is not scope memory. */
    private static long block;
    private static final int VM_BUF = 0, N_VMS = 8, JVM_NAME = 16, FN_NAME = 48, BLOCK = 80;

    @Unsafe("turns ExitHook.onExit into a C function pointer; AtExitFn declares its void(void) signature; fills the handler's block within its BLOCK bytes")
    static void install() {
        jrThread = WinApi.getCurrentThreadId();
        handedBack = false;
        if (block == 0) {
            var b = WinApi.malloc(BLOCK);
            if (b.toLong() == 0) {
                return; // no hook: a failed start then exits as jli decides, without a retry
            }
            Address.fillZero(b, BLOCK);
            put(b.add(JVM_NAME), "jvm.dll", 2);
            put(b.add(FN_NAME), "JNI_GetCreatedJavaVMs", 1);
            block = b.toLong();
        }
        var handler = (Address) (Object) Function.get(AtExitFn.class, ExitHook.class, "onExit");
        register("ucrtbase.dll", "_crt_atexit", handler);
        register("msvcr120.dll", "atexit", handler);
        register("msvcr100.dll", "atexit", handler);
        armed = true;
    }

    /** ASCII text in 1- or 2-byte units; the terminating NUL is already there (the block is zeroed). */
    @Unsafe("writes text.length() units at p; install sizes the block so the NUL after them stays inside it")
    private static void put(Address p, String text, int width) {
        for (var i = 0; i < text.length(); i++) {
            if (width == 2) p.add(i * 2).putShort((short) text.charAt(i));
            else p.add(i).putByte((byte) text.charAt(i));
        }
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

    /** Runs inside exit(), on whichever thread called it. Unmanaged: see the class comment. */
    @Unmanaged
    @ForeignThread("the CRT runs atexit handlers on the thread that calls exit(): in jvm=dll mode usually the JVM's. This method is @Unmanaged and runs no TeaVM code there; on jr's own thread it calls Launch.onJvmExit")
    @Unsafe("reads install's block; calls jvm.dll's JNI_GetCreatedJavaVMs through a pointer from GetProcAddress, as JniGetCreatedVmsFn declares")
    static void onExit() {
        if (!armed) {
            return;
        }
        if (WinApi.getCurrentThreadId() == jrThread) {
            // jli exited on jr's own thread (an error before it started the JavaMain thread): an ordinary callback.
            armed = false;
            Launch.onJvmExit();
            return;
        }
        var b = Address.fromLong(block);
        var jvm = WinApi.getModuleHandleW(b.add(JVM_NAME));
        if (jvm.toLong() != 0) {
            var fn = WinApi.getProcAddress(jvm, b.add(FN_NAME));
            if (fn.toLong() == 0) {
                return;
            }
            ((JniGetCreatedVmsFn) (Object) fn).invoke(b.add(VM_BUF), 1, b.add(N_VMS));
            if (b.add(N_VMS).getInt() > 0) {
                return; // a JVM exists: the app chose to exit, and the exit goes ahead
            }
        }
        // No JVM: it failed to start. End this thread instead of the process; JLI_Launch then returns on jr's thread.
        armed = false;
        handedBack = true;
        WinApi.exitThread(1);
    }
}
