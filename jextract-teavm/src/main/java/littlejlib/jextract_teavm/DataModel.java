package littlejlib.jextract_teavm;

import org.openjdk.jextract.Type.Primitive.Kind;

public enum DataModel {
    LLP64(4, 2), LP64(8, 4);

    final int longSize, wcharSize;

    DataModel(int longSize, int wcharSize) {
        this.longSize = longSize;
        this.wcharSize = wcharSize;
    }

    static DataModel of(String targetTriple) {
        var t = targetTriple.toLowerCase();
        return t.contains("windows") || t.contains("mingw") || t.contains("msvc") ? LLP64 : LP64;
    }

    int size(Kind k) {
        return switch (k) {
            case Void -> 0;
            case Bool, Char -> 1;
            case Short, Char16, HalfFloat -> 2;
            case Int, Float -> 4;
            case Long -> longSize;
            case LongLong, Double -> 8;
            case WChar -> wcharSize;
            case LongDouble, Int128, Float128 -> 16;
        };
    }
}
