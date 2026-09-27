package pocapp.jr;

import org.teavm.interop.Address;

import static pocapp.jr.N.*;

/** Progress bar matching how jr was launched: a text bar in console mode, a small native
 *  progress-bar window in GUI mode - mirrors javainstall.c's JiProgress. The GUI frame is a
 *  plain window of the pre-registered "#32770" (dialog) class rather than a custom-registered
 *  window class, so no WNDPROC function pointer is needed at all - simpler than the C version,
 *  and one less thing that needs a callback-into-Java mechanism this port doesn't have yet.
 *  hwndWindow/hwndBar are plain named fields (never array elements) - see guidelines.teavmcpp.md
 *  on why an Address must never be stored in an array on this backend. */
public final class Progress {
    private final boolean guiMode;
    private final boolean hasConsole;
    private long lastShownPercent = -1;
    private Address hwndWindow;
    private Address hwndBar;

    public Progress(boolean guiMode, boolean hasConsole, String label) {
        this.guiMode = guiMode;
        this.hasConsole = hasConsole;

        if (guiMode) {
            var iccex = alloc(8);
            iccex.putInt(8);
            iccex.add(4).putInt(WinApi.ICC_PROGRESS_CLASS);
            WinApi.initCommonControlsEx(iccex);

            hwndWindow = WinApi.createWindowExA(WinApi.WS_EX_TOPMOST, cstr("#32770"),
                    cstr("Java Runner - Installing Java"), WinApi.WS_CAPTION,
                    WinApi.CW_USEDEFAULT, WinApi.CW_USEDEFAULT, 420, 120,
                    NULL, NULL, NULL, NULL);

            if (hwndWindow.toLong() != 0) {
                WinApi.createWindowExA(0, cstr("STATIC"), cstr(label), WinApi.WS_CHILD | WinApi.WS_VISIBLE,
                        10, 10, 390, 20, hwndWindow, NULL, NULL, NULL);
                hwndBar = WinApi.createWindowExA(0, cstr("msctls_progress32"), NULL,
                        WinApi.WS_CHILD | WinApi.WS_VISIBLE, 10, 40, 390, 24,
                        hwndWindow, NULL, NULL, NULL);
                WinApi.sendMessageA(hwndBar, WinApi.PBM_SETRANGE, 0L, 0x00640000L);
                WinApi.showWindow(hwndWindow, WinApi.SW_SHOW);
                WinApi.updateWindow(hwndWindow);
            }
        } else if (hasConsole) {
            System.out.println(label);
        }
    }

    public void update(long downloaded, long total) {
        if (total <= 0) {
            if (guiMode) {
                pumpMessages();
            }
            return;
        }
        var percent = (downloaded * 100) / total;
        if (percent == lastShownPercent) {
            if (guiMode) {
                pumpMessages();
            }
            return;
        }
        lastShownPercent = percent;

        if (guiMode) {
            if (hwndBar != null && hwndBar.toLong() != 0) {
                WinApi.sendMessageA(hwndBar, WinApi.PBM_SETPOS, percent, 0L);
            }
            pumpMessages();
        } else if (hasConsole) {
            var barWidth = 30;
            var filled = (int) ((percent * barWidth) / 100);
            var sb = new StringBuilder();
            sb.append('\r').append('[');
            for (var i = 0; i < barWidth; i++) {
                sb.append(i < filled ? '#' : '-');
            }
            sb.append("] ").append(percent).append("% (")
                    .append(downloaded / (1024 * 1024)).append(" MB / ")
                    .append(total / (1024 * 1024)).append(" MB)");
            System.out.print(sb);
        }
    }

    public void finish() {
        if (guiMode) {
            if (hwndWindow != null && hwndWindow.toLong() != 0) {
                WinApi.destroyWindow(hwndWindow);
            }
        } else if (hasConsole) {
            System.out.println();
        }
    }

    private void pumpMessages() {
        memScoped(() -> {
            var msg = alloc(WinOffsets.MSG.SIZE);
            while (WinApi.peekMessageA(msg, NULL, 0, 0, WinApi.PM_REMOVE) != 0) {
                WinApi.translateMessage(msg);
                WinApi.dispatchMessageA(msg);
            }
        });
    }
}
