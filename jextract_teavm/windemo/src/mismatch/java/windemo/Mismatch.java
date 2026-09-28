package windemo;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

import static windemo.bind.Win.*;

/** Does TeaVM check the method named in Function.get against the callback type's invoke(...)? */
public class Mismatch {
    static int calls;

    public static void main(String[] args) {
        enumWindows((Address) (Object) Function.get(WNDENUMPROC.class, Mismatch.class, "wrong"), 0);
        System.out.println("calls: " + calls);
    }

    static int wrong(Address hwnd) {
        calls++;
        return 0;
    }
}
