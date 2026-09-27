package demo;

import wintype.WinType;

/** DWORD_PTR is an 8-byte integer typedef (despite the "DWORD" in its name suggesting 4 bytes) -
 *  declaring it as Java `int` is a width mismatch, not a pointer/integer mismatch. Confirms
 *  WinTypeProcessor catches width too, not just pointer-vs-integer. EXPECTED to fail. */
public class BadWidth {
    static native void setWindowLongPtr(@WinType(value = "DWORD_PTR") int context);
}
