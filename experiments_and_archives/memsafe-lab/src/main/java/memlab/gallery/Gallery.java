package memlab.gallery;

import pocapp.jr.*;

import static memlab.Lab.report;

public final class Gallery {
    private Gallery() {}

    public static void run() {
        for (var chaos : new boolean[] {false, true}) {
            Chaos.on = chaos;
            var tag = chaos ? "+gc" : "";
            report("site.FileInfo" + tag, "" + FileInfo.lastWriteTimeRaw("C:\\Windows\\notepad.exe"),
                    "" + Sites.lastWriteTimeRaw("C:\\Windows\\notepad.exe"));
            report("site.Dirs.findSingleSubdir" + tag, "" + Dirs.findSingleSubdir("C:\\Windows"),
                    "" + Sites.findSingleSubdir("C:\\Windows"));
            report("site.ProcessLauncher" + tag, "" + ProcessLauncher.runHiddenAndWait("cmd /c exit 0"),
                    "" + (Sites.runAndWait("cmd /c exit 0") == 0));
            report("site.ProcessLauncher.exit7" + tag, "7", "" + Sites.runAndWait("cmd /c exit 7"));
            report("site.NativeArch" + tag, NativeArch.foojayName(), Sites.nativeArch());
            Sites.aotEnv("C:\\x\\app.aot", chaos);
            report("site.CmdLineBuilder.env" + tag, "C:\\x\\app.aot " + (chaos ? "using" : "creating"),
                    Win.env("JR_AOT_CACHE") + " " + Win.env("JR_AOT_STATE"));
        }
        Chaos.on = false;
    }
}
