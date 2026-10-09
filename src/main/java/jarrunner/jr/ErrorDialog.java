package jarrunner.jr;

import org.teavm.interop.Address;

import static jarrunner.jr.N.*;

/** PRP-31: the one window every jr error uses in GUI mode. A plain "#32770" window and a message loop, no
 *  WNDPROC (see Progress): clicks and keys are read off the queue. The report box takes typing, so every
 *  action is an F-key, worn on its button; Esc closes, Tab moves. It closes itself after a countdown,
 *  which stops the moment the user touches the window, so a tool failing in a loop leaves no pile of them. */
public final class ErrorDialog {
    private ErrorDialog() {}

    private static final int COUNTDOWN_S = 35, CLOSE = 1, COPY = 2, EMAIL = 3, ISSUE = 4, FOLDER = 5, DOCTOR = 6, REPAIR = 7;

    // Address fields, never an Address[] (guidelines.teavmcpp.md)
    @Handle private static Address dlg, edit, status, bClose, bCopy, bEmail, bIssue, bFolder, bDoctor, bRepair;
    private static String title = "";
    private static int dpi = 96;
    @Handle private static Address font;

    static void show(String windowTitle, String summary, String report, String savedPath) {
        title = windowTitle;
        var c = ErrorReport.config;
        var email = c == null ? "" : c.supportEmail;
        var issues = c == null ? "" : !c.supportIssues.isEmpty() ? c.supportIssues : c.supportUrl;
        initLook();
        dlg = WinApi.createWindowExW(0, "#32770", ExeInfo.baseNameNoExt() + " - " + windowTitle, WinApi.WS_CAPTION | WinApi.WS_SYSMENU, WinApi.CW_USEDEFAULT, WinApi.CW_USEDEFAULT, s(700), s(560), NULL, NULL, NULL, NULL);
        if (dlg.toLong() == 0) {
            WinApi.messageBoxW(NULL, report, windowTitle, WinApi.MB_ICONERROR);
            return;
        }
        WinIcons.set(dlg);
        control("STATIC", summary, 0, 12, 10, 660, 48);
        var who = c == null ? "" : (c.supportName + "  " + email + "  " + issues).strip();
        control("STATIC", who.isEmpty() ? "" : "Support: " + who, 0, 12, 60, 660, 18);
        edit = control("EDIT", "", WinApi.ES_MULTILINE | WinApi.ES_AUTOVSCROLL | WinApi.ES_WANTRETURN | WinApi.WS_VSCROLL
                | WinApi.WS_TABSTOP, 12, 82, 660, 340);
        setText(report);
        var x = 12;
        bCopy = button("F2 Copy", x, 80); x += 86;
        bEmail = email.isEmpty() ? NULL : button("F3 Email", x, 80); x += email.isEmpty() ? 0 : 86;
        bIssue = issues.isEmpty() ? NULL : button("F4 Report issue", x, 104); x += issues.isEmpty() ? 0 : 110;
        bFolder = button("F5 Reports folder", x, 112); x += 118;
        bDoctor = c == null ? NULL : button("F6 Doctor", x, 80); x += c == null ? 0 : 86;
        bRepair = c == null ? NULL : button("F7 Repair", x, 80);
        bClose = button("Esc Close", 568, 104);
        status = control("STATIC", savedPath.isEmpty() ? "" : ErrorReport.redact("Saved: " + savedPath), 0, 12, 470, 660, 36);
        WinApi.showWindow(dlg, WinApi.SW_SHOWNORMAL);
        WinApi.setForegroundWindow(dlg);
        WinApi.setFocus(bClose);
        loop(email, issues);
        WinApi.destroyWindow(dlg);
    }

    private static void loop(String email, String issues) {
        memScoped(() -> {
            var msg = alloc(WinOffsets.MSG.SIZE);
            var end = WinApi.getTickCount64() + COUNTDOWN_S * 1000L;
            var counting = true;
            var shown = -1L;
            while (WinApi.isWindowVisible(dlg) != 0) {
                if (counting) {
                    var left = (end - WinApi.getTickCount64() + 999) / 1000;
                    if (left <= 0) return;
                    if (left != shown) WinApi.setWindowTextW(bClose, "Esc Close (" + left + ")");
                    shown = left;
                }
                WinApi.msgWaitForMultipleObjects(0, NULL, 0, 200, WinApi.QS_ALLINPUT);
                while (WinApi.peekMessageW(msg, NULL, 0, 0, WinApi.PM_REMOVE) != 0) {
                    var h = WinOffsets.MSG.hwnd(msg);
                    var m = WinOffsets.MSG.message(msg);
                    var ours = h.toLong() == dlg.toLong() || WinApi.isChild(dlg, h) != 0;
                    if (ours && counting && (m == WinApi.WM_KEYDOWN || m == WinApi.WM_LBUTTONDOWN)) {
                        counting = false;
                        WinApi.setWindowTextW(bClose, "Esc Close");
                    }
                    var action = ours ? action(h, m, WinOffsets.MSG.wParam(msg)) : 0;
                    if (action == CLOSE) return;
                    var swallow = action != 0 && m == WinApi.WM_KEYDOWN || m == WinApi.WM_NCLBUTTONDOWN && action != 0;
                    if (!swallow && WinApi.isDialogMessageW(dlg, msg) == 0) {
                        WinApi.translateMessage(msg);
                        WinApi.dispatchMessageW(msg);
                    }
                    if (action != 0) run(action, email, issues);
                }
            }
        });
    }

    private static int action(Address h, int m, long w) {
        if (m == WinApi.WM_NCLBUTTONDOWN && w == WinApi.HTCLOSE) return CLOSE;
        if (m == WinApi.WM_KEYDOWN) {
            var k = (int) w;
            if (k == WinApi.VK_ESCAPE) return CLOSE;
            if (k >= WinApi.VK_F2 && k <= WinApi.VK_F7) return available(k - WinApi.VK_F2 + COPY);
            if (k == WinApi.VK_RETURN || k == WinApi.VK_SPACE) return buttonAction(h);
        }
        return m == WinApi.WM_LBUTTONUP ? buttonAction(h) : 0;
    }

    private static int buttonAction(Address h) {
        var v = h.toLong();
        return v == 0 ? 0 : v == bClose.toLong() ? CLOSE : v == bCopy.toLong() ? COPY : v == bEmail.toLong() ? EMAIL
                : v == bIssue.toLong() ? ISSUE : v == bFolder.toLong() ? FOLDER : v == bDoctor.toLong() ? DOCTOR
                : v == bRepair.toLong() ? REPAIR : 0;
    }

    private static int available(int a) {
        var b = a == EMAIL ? bEmail : a == ISSUE ? bIssue : a == DOCTOR ? bDoctor : a == REPAIR ? bRepair : bCopy;
        return b.toLong() != 0 ? a : 0;
    }

    private static void run(int action, String email, String issues) {
        var text = getText();
        switch (action) {
            case COPY -> say("Copied. Paste it into an email or an issue.");
            case EMAIL -> open("mailto:" + email + "?subject=" + Url.encode(title) + "&body="
                    + Url.encode(cut(text, 1500) + "\n\n(The full report is on the clipboard; paste it here.)"));
            case ISSUE -> open(issues.contains("github.com") ? issues + (issues.endsWith("/issues") ? "/new" : "")
                    + "?title=" + Url.encode(title) + "&body=" + Url.encode(cut(text, 4000)) : issues);
            case FOLDER -> open(JrDirs.of("reports"));
            case DOCTOR -> setText(ErrorReport.redact(Doctor.report(ErrorReport.config, "see the report above", ErrorReport.javaExeName)));
            case REPAIR -> setText(ErrorReport.redact(Repair.run(ErrorReport.config, ErrorReport.javaExeName, false, true)));
            default -> { }
        }
        if (action == COPY || action == EMAIL || action == ISSUE) {
            Ui.copy(text.replace("\n", "\r\n"));
        }
    }

    private static void open(String target) {
        if (target != null) {
            WinApi.shellExecuteW(dlg, wcstr("open"), wcstr(target), NULL, NULL, WinApi.SW_SHOWNORMAL);
            say("Opened. The full report is also on the clipboard.");
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n[...]";
    }

    private static void say(String s) {
        WinApi.setWindowTextW(status, s);
    }

    private static void setText(String s) {
        WinApi.setWindowTextW(edit, s.replace("\r\n", "\n").replace("\n", "\r\n"));
    }

    private static String getText() {
        var n = WinApi.getWindowTextLengthW(edit) + 1;
        var buf = alloc(n * 2);
        WinApi.getWindowTextW(edit, buf, n);
        return wstring(buf, n).replace("\r\n", "\n");
    }

    private static Address button(String label, int x, int w) {
        return control("BUTTON", label, WinApi.BS_PUSHBUTTON | WinApi.WS_TABSTOP, x, 432, w, 28);
    }

    private static Address control(String cls, String text, int style, int x, int y, int w, int h) {
        var exStyle = cls.equals("EDIT") ? WinApi.WS_EX_CLIENTEDGE : 0;
        var hwnd = WinApi.createWindowExW(exStyle, cls, text, WinApi.WS_CHILD | WinApi.WS_VISIBLE | style, s(x), s(y), s(w), s(h), dlg, NULL, NULL, NULL);
        if (hwnd.toLong() != 0) {
            WinApi.sendMessageW(hwnd, WinApi.WM_SETFONT, font.toLong(), 1L);
        }
        return hwnd;
    }
    /** The system's message font (Segoe UI on Windows 10/11) and the screen DPI, so the window is drawn natively,
     *  not bitmap-stretched; jr's manifest declares per-monitor DPI awareness, as java.exe's does. */
    private static void initLook() {
        var dc = WinApi.getDC(NULL);
        dpi = 96;
        if (dc.toLong() != 0) {
            dpi = Math.max(96, WinApi.getDeviceCaps(dc, WinApi.LOGPIXELSY));
            WinApi.releaseDC(NULL, dc);
        }
        var ncm = alloc(WinOffsets.NONCLIENTMETRICSW.SIZE);
        WinOffsets.NONCLIENTMETRICSW.cbSize(ncm, WinOffsets.NONCLIENTMETRICSW.SIZE);
        var logFont = WinOffsets.NONCLIENTMETRICSW.lfMessageFont(ncm);
        font = WinApi.systemParametersInfoW(WinApi.SPI_GETNONCLIENTMETRICS, WinOffsets.NONCLIENTMETRICSW.SIZE, ncm, 0) != 0
                ? WinApi.createFontIndirectW(logFont) : NULL;
        if (font.toLong() == 0) {
            font = WinApi.getStockObject(WinApi.DEFAULT_GUI_FONT);
        }
    }

    /** A 96-DPI layout size in this screen's pixels. */
    private static int s(int v) {
        return v * dpi / 96;
    }
}
