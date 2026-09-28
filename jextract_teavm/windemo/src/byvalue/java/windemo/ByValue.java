package windemo;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.Structure;

/** PRP-19 item 3: can a TeaVM Structure cross an @Import BY VALUE? POINT in (WindowFromPoint), div_t out (div). */
public class ByValue {
    public static class Point extends Structure {
        public int x, y;
    }

    public static class DivT extends Structure {
        public int quot, rem;
    }

    @Import(name = "WindowFromPoint") static native Address windowFromPoint(Point p);
    @Import(name = "div") static native DivT div(int num, int den);

    public static void main(String[] args) {
        var buf = new byte[8];
        Point p = Address.ofData(buf).toStructure();
        p.x = 10;
        p.y = 10;
        System.out.println("WindowFromPoint(10,10) = " + windowFromPoint(p).toLong());
        var d = div(17, 5);
        System.out.println("div(17, 5) = " + d.quot + " rem " + d.rem);
    }
}
