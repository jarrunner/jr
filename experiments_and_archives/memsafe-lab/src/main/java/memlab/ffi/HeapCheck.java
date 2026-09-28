package memlab.ffi;

import org.teavm.interop.Address;
import org.teavm.runtime.GC;

public final class HeapCheck {
    private HeapCheck() {}

    public static boolean on = true;
    public static int violations;

    public static boolean inHeap(Address a) {
        var p = a.toLong();
        var lo = GC.heapAddress().toLong();
        return p >= lo && p < lo + GC.maxAvailableBytes();
    }

    public static Address arg(Address a, String fn, int index) {
        if (on && inHeap(a)) {
            violations++;
            System.out.println("      [heapcheck] " + fn + " arg " + index + " points into the GC heap (0x"
                    + Long.toHexString(a.toLong()) + ")");
        }
        return a;
    }
}
