package memlab.gallery;

import pocapp.jr.*;

import static memlab.Lab.report;

public final class Gallery2 {
    private Gallery2() {}

    public static void run() {
        for (var chaos : new boolean[] {false, true}) {
            Chaos.on = chaos;
            var tag = chaos ? "+gc" : "";
            report("v2.FileInfo" + tag, "" + FileInfo.lastWriteTimeRaw("C:\\Windows\\notepad.exe"),
                    "" + Sites2.lastWriteTimeRaw("C:\\Windows\\notepad.exe"));
            report("v2.Dirs.findSingleSubdir" + tag, "" + Dirs.findSingleSubdir("C:\\Windows"),
                    "" + Sites2.findSingleSubdir("C:\\Windows"));
            report("v2.ProcessLauncher.exit7" + tag, "7", "" + Sites2.runAndWait("cmd /c exit 7"));
            report("v2.NativeArch" + tag, NativeArch.foojayName(), Sites2.nativeArch());
            Sites2.aotEnv("C:\\y\\app.aot", chaos);
            report("v2.CmdLineBuilder.env" + tag, "C:\\y\\app.aot " + (chaos ? "using" : "creating"),
                    Win.env("JR_AOT_CACHE") + " " + Win.env("JR_AOT_STATE"));
            report("v2.cstrArray" + tag, "java,-jar,app.jar,two words,", Sites2.argvRoundTrip());
        }
        Chaos.on = false;
    }
}
