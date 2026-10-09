package jarrunner.jr;

import static jarrunner.jr.N.*;

/** PRP-31: the text behind every jr error - what went wrong, what jr tried, what Java printed, where the logs
 *  are - redacted (profile path, user and computer name, secret-looking values) before anyone sees it,
 *  saved under %USERPROFILE%\.jr\reports so it outlives the dialog, and counted so a launch loop cannot
 *  pile up dialogs. */
public final class ErrorReport {
    private ErrorReport() {}

    private static final int MAX_DIALOGS = 3, WINDOW_SECONDS = 300;
    private static final String[] SECRETS = {"password", "passwd", "token", "secret", "apikey", "api_key"};

    static Config config; // set by Jr once the config is loaded; support contact, doctor and repair need it
    static String javaExeName = "java.exe";

    static String build(String title, String message) {
        var sb = new StringBuilder(title).append('\n').append(message).append("\n\n");
        sb.append("Exe: ").append(ExeInfo.fullPath()).append('\n');
        if (config != null && (!config.appId.isEmpty() || !config.appVersion.isEmpty())) {
            sb.append("App: ").append(config.appId).append(' ').append(config.appVersion).append('\n');
        }
        sb.append("Time (UTC): ").append(Epoch.formatNow()).append('\n');
        if (Launch.chooser != null) {
            sb.append("Java found:\n").append(Launch.chooser.report());
        }
        if (!StartCapture.file.isEmpty()) {
            sb.append("Java output (GUI mode): ").append(StartCapture.file).append('\n');
        }
        var crash = JrDirs.of("crash");
        if (crash != null && Dirs.matchCount(crash, ExeInfo.baseNameNoExt() + "-hs_err_pid*.log") > 0) {
            sb.append("Java crash logs: ").append(crash).append('\n');
        }
        return redact(sb.toString());
    }

    /** The profile path, user and computer names (whole words only) masked; a secret-looking key=value has its value removed. */
    static String redact(String text) {
        var t = replaceIgnoreCase(text, Cstr.readEnv("USERPROFILE"), "%USERPROFILE%");
        t = replaceWord(t, Cstr.readEnv("USERNAME"), "<user>");
        t = replaceWord(t, Cstr.readEnv("COMPUTERNAME"), "<computer>");
        for (var key : SECRETS) {
            var at = 0;
            while ((at = AsciiStr.lower(t).indexOf(key, at)) >= 0) {
                var eq = at + key.length();
                while (eq < t.length() && word(t.charAt(eq))) eq++;
                if (eq < t.length() && (t.charAt(eq) == '=' || t.charAt(eq) == ':')) {
                    var end = eq + 1;
                    while (end < t.length() && t.charAt(end) > ' ') end++;
                    t = t.substring(0, eq + 1) + "<removed>" + t.substring(end);
                }
                at = eq;
            }
        }
        return t;
    }

    private static boolean word(char c) {
        return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    /** Case-sensitive and whole words only: a user called "User" must not turn every "user" into "<user>". */
    private static String replaceWord(String text, String what, String with) {
        if (what == null || what.length() < 3) {
            return text;
        }
        var sb = new StringBuilder();
        var from = 0;
        for (var at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + 1)) {
            var end = at + what.length();
            if (at < from || at > 0 && word(text.charAt(at - 1)) || end < text.length() && word(text.charAt(end))) {
                continue;
            }
            sb.append(text, from, at).append(with);
            from = end;
        }
        return sb.append(text.substring(from)).toString();
    }

    private static String replaceIgnoreCase(String text, String what, String with) {
        if (what == null || what.length() < 3) {
            return text;
        }
        var lower = AsciiStr.lower(text);
        var w = AsciiStr.lower(what);
        var sb = new StringBuilder();
        var from = 0;
        for (var at = lower.indexOf(w); at >= 0; at = lower.indexOf(w, from)) {
            sb.append(text, from, at).append(with);
            from = at + w.length();
        }
        return sb.append(text.substring(from)).toString();
    }

    /** Saved as reports\<exe>-<time>.txt; returns the path, or "" if it could not be written. */
    static String save(String report) {
        var dir = JrDirs.of("reports");
        if (dir == null) {
            return "";
        }
        var stamp = Epoch.formatNow().replace(' ', '_').replace(':', '-');
        var path = dir + "\\" + ExeInfo.baseNameNoExt() + "-" + stamp + ".txt";
        return FileIo.writeAll(path, report) ? path : ""; // fopen "w" is text mode: \n is written as \r\n
    }

    /** True when this exe already showed MAX_DIALOGS dialogs in the last WINDOW_SECONDS; records this one. */
    static boolean tooMany() {
        var dir = JrDirs.of("reports");
        if (dir == null) {
            return false;
        }
        var file = dir + "\\" + ExeInfo.baseNameNoExt() + ".recent";
        var now = WinApi.time(NULL);
        var recent = new StringBuilder();
        var count = 0;
        var old = FileIo.readAll(file);
        for (var line : Lines.split(old == null ? "" : old)) {
            var t = (long) Atoi.parse(line);
            if (t > now - WINDOW_SECONDS) {
                recent.append(t).append('\n');
                count++;
            }
        }
        FileIo.writeAll(file, recent.append(now).append('\n').toString());
        return count >= MAX_DIALOGS;
    }
}
