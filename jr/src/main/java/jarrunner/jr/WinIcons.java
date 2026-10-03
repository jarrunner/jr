package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** A window's caption and taskbar icons from this exe's own icon (a client's included). jr stamps one 256x256
 *  entry; LoadIconWithScaleDown shrinks it with proper filtering to the exact size this DPI wants, where
 *  ExtractIconEx's own scaling left the 16-pixel caption icon visibly pixelated (PRP-31). */
public final class WinIcons {
    private WinIcons() {}

    static void set(Address hwnd) {
        var self = WinApi.getModuleHandleA(NULL);
        var group = Address.fromLong(1); // MAKEINTRESOURCE(1): the RT_GROUP_ICON jr stamps
        var small = ptrVar();
        var large = ptrVar();
        var comctl = WinApi.getModuleHandleA(cstr("comctl32.dll"));
        var fn = comctl.toLong() == 0 ? NULL : WinApi.getProcAddress(comctl, cstr("LoadIconWithScaleDown"));
        var ok = false;
        if (fn.toLong() != 0) {
            var load = (LoadIconScaleDownFn) (Object) fn;
            var sm = WinApi.getSystemMetrics(WinApi.SM_CXSMICON);
            var lg = WinApi.getSystemMetrics(WinApi.SM_CXICON);
            ok = load.invoke(self, group, sm, sm, small) >= 0 & load.invoke(self, group, lg, lg, large) >= 0;
        }
        if (!ok && WinApi.extractIconExW(wcstr(ExeInfo.fullPath()), 0, large, small, 1) <= 0) {
            return;
        }
        WinApi.sendMessageA(hwnd, WinApi.WM_SETICON, WinApi.ICON_SMALL, small.getAddress().toLong());
        WinApi.sendMessageA(hwnd, WinApi.WM_SETICON, WinApi.ICON_BIG, large.getAddress().toLong());
    }
}
