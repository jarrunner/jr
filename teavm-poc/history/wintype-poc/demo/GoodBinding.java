package demo;

import wintype.Address;
import wintype.WinType;

/** The corrected version of BadBinding - WPARAM/LPARAM as `long` (matching the real 8-byte
 *  integer typedefs), HWND correctly kept as Address (a real pointer). EXPECTED to compile clean:
 *  every @WinType-annotated parameter here actually matches what the real header says. */
public class GoodBinding {
    static native long sendMessageA(
            @WinType(value = "HWND") Address hwnd,
            int msg,
            @WinType(value = "WPARAM") long wParam,
            @WinType(value = "LPARAM") long lParam);
}
