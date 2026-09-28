package memlab.probe;

import java.util.function.Supplier;
import memlab.Old;
import org.teavm.interop.Address;

public final class GenericEscape {
    public static void main(String[] args) {
        Supplier<Address> s = () -> Old.cstr("escaped-through-a-generic");
        var a = s.get();
        System.out.println(Old.read(a));
    }
}
