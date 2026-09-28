package littlejlib.jr;

import static littlejlib.jr.N.*;

/**
 * Formats the current UTC time from a raw time(NULL) epoch-seconds value using plain calendar
 * math (Howard Hinnant's civil_from_days algorithm) - avoids CRT struct tm/localtime entirely.
 */
public final class Epoch {
    private Epoch() {}

    public static String formatNow() {
        var secs = WinApi.time(NULL);
        return format(secs);
    }

    static String format(long epochSeconds) {
        var days = Math.floorDiv(epochSeconds, 86400L);
        var secOfDay = Math.floorMod(epochSeconds, 86400L);
        var ymd = civilFromDays(days);
        var hh = secOfDay / 3600;
        var mm = (secOfDay % 3600) / 60;
        var ss = secOfDay % 60;
        return pad(ymd[0], 4) + "-" + pad(ymd[1], 2) + "-" + pad(ymd[2], 2)
                + " " + pad(hh, 2) + ":" + pad(mm, 2) + ":" + pad(ss, 2);
    }

    private static String pad(long v, int width) {
        var s = Long.toString(v);
        while (s.length() < width) {
            s = "0" + s;
        }
        return s;
    }

    private static long[] civilFromDays(long z) {
        z += 719468;
        var era = (z >= 0 ? z : z - 146096) / 146097;
        var doe = z - era * 146097;
        var yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        var y = yoe + era * 400;
        var doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        var mp = (5 * doy + 2) / 153;
        var d = doy - (153 * mp + 2) / 5 + 1;
        var m = mp + (mp < 10 ? 3 : -9);
        y += (m <= 2 ? 1 : 0);
        return new long[] {y, m, d};
    }
}
