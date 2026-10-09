package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** A window's caption and taskbar icons from this exe's own icon (a client's included). jr stamps one 256x256
 *  entry; LoadIconWithScaleDown shrinks it with proper filtering to the exact size this DPI wants, where
 *  ExtractIconEx's own scaling left the 16-pixel caption icon visibly pixelated (PRP-31). */
public final class WinIcons {
    private WinIcons() {}

    @Unsafe("trusts that comctl32's LoadIconWithScaleDown has the signature LoadIconScaleDownFn declares")
    static void set(Address hwnd) {
        var self = WinApi.getModuleHandleW(NULL);
        if (self.toLong() == 0) {
            return;
        }
        var group = intResource(1); // the RT_GROUP_ICON jr stamps
        var small = ptrVar();
        var large = ptrVar();
        var comctl = WinApi.getModuleHandleW("comctl32.dll");
        var fn = comctl.toLong() == 0 ? NULL : WinApi.getProcAddress(comctl, "LoadIconWithScaleDown");
        var ok = false;
        if (fn.toLong() != 0) {
            var load = (LoadIconScaleDownFn) (Object) fn;
            var sm = WinApi.getSystemMetrics(WinApi.SM_CXSMICON);
            var lg = WinApi.getSystemMetrics(WinApi.SM_CXICON);
            ok = load.invoke(self, group, sm, sm, small) >= 0 & load.invoke(self, group, lg, lg, large) >= 0;
        }
        if (!ok && WinApi.extractIconExW(ExeInfo.fullPath(), 0, large, small, 1) <= 0) {
            return;
        }
        WinApi.sendMessageW(hwnd, WinApi.WM_SETICON, WinApi.ICON_SMALL, ptrOf(small).toLong());
        WinApi.sendMessageW(hwnd, WinApi.WM_SETICON, WinApi.ICON_BIG, ptrOf(large).toLong());
    }
}
