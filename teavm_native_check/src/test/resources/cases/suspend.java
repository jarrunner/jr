// expect: NC5-suspend NC5-suspend NC5-suspend NC5-suspend
package t;

import org.teavm.interop.Address;
import org.teavm.interop.Async;
import static t.N.*;

class Suspend {
    static final Object lock = new Object();

    @Async static native void pause();

    static void run() {
        memScoped(() -> {
            var p = alloc(4);
            try {
                Thread.sleep(1);
                lock.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            synchronized (lock) { os(p); }
            pause();
        });
    }
}
