package memlab;

public final class Lab {
    private Lab() {}

    static int pass, fail;

    public static void report(String name, String expected, String got) {
        var ok = expected.equals(got);
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  ok   " : "  BAD  ") + "[" + name + "] expected=\"" + expected + "\" got=\"" + got + "\"");
    }

    public static void main(String[] args) {
        var what = args.length > 0 ? args[0] : "all";
        var all = what.equals("all");
        if (all || what.equals("api")) {
            System.out.println("== the same things through Ffi (lambda-scoped off-heap arena) ==");
            ApiDemos.two();
            ApiDemos.escape();
            ApiDemos.nest();
            ApiDemos.argv();
            ApiDemos.wide();
            ApiDemos.thrown();
            WriteHazard.ffi();
        }
        if (all || what.equals("priorart")) {
            System.out.println("== copied from prior art: cgocheck-style heap check, LibraryImport-style String overloads ==");
            PriorArt.heapCheck();
            PriorArt.generatedStringOverloads();
        }
        if (all || what.equals("real")) {
            System.out.println("== a real teavm-poc site: FileInfo.lastWriteTimeRaw ==");
            RealSite.run(args.length > 1 ? args[1] : "C:\\Windows\\notepad.exe", false);
        }
        if (what.equals("crash")) {
            System.out.println("== today's FileInfo shape with a GC where one could land (may segfault) ==");
            RealSite.run(args.length > 1 ? args[1] : "C:\\Windows\\notepad.exe", true);
        }
        if (all || what.equals("gallery")) {
            System.out.println("== teavm-poc sites rewritten on Ffi, against the untouched originals ==");
            memlab.gallery.Gallery.run();
            memlab.gallery.Gallery2.run();
        }
        if (all || what.equals("bench")) {
            RealSite.bench(args.length > 1 && !all ? Integer.parseInt(args[1]) : 200_000);
        }
        if (all || what.equals("hazards")) {
            System.out.println("== today's shapes, with a GC forced where one could land ==");
            Hazards.free();
            Hazards.move();
            Hazards.two();
            Hazards.live();
            Hazards.pin();
            WriteHazard.old();
        }
        System.out.println("passed " + pass + ", bad " + fail);
        memlab.nat.N.checkLeaks();
        memlab.ffi.Ffi.checkLeaks();
    }
}
