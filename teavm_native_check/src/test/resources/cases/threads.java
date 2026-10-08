// expect: NC9-callback NC9-foreign NC9-foreign NC9-foreign
package t;

import org.teavm.interop.Function;
import static t.N.*;

class Threads {
    abstract static class Cb extends Function { abstract int invoke(int x); }

    static boolean flag;

    static void register() {
        Function.get(Cb.class, Threads.class, "onSame");
        Function.get(Cb.class, Threads.class, "onForeign");
        Function.get(Cb.class, Threads.class, "unmarked");
    }

    @SameThread("EnumThings calls it before returning")
    static int onSame(int x) { memScoped(() -> { alloc(8); }); return x; }

    @ForeignThread("the runtime's exit thread; ours is blocked meanwhile")
    static int onForeign(int x) { flag = true; return x; }

    static int unmarked(int x) { return x; }

    @ForeignThread("a worker thread")
    static int bad(int x) throws InterruptedException {
        memScoped(() -> { alloc(8); });
        Thread.sleep(1);
        synchronized (Threads.class) { flag = true; }
        return x;
    }
}
