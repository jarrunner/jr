package demo;

import wintype.Address;
import wintype.WinType;

/** Reproduces PRP-09's ACTUAL bug verbatim: WPARAM/LPARAM declared as Address (pointer-shaped)
 *  when the real windows.h defines them as 8-byte integer typedefs. This file is EXPECTED to fail
 *  compilation with WinTypeProcessor on the annotation processor path - that's the whole point. */
public class BadBinding {
    static native long sendMessageA(
            Address hwnd,
            int msg,
            @WinType(value = "WPARAM") Address wParam,
            @WinType(value = "LPARAM") Address lParam);
}
