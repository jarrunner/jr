package memlab;

import memlab.ffi.F;
import memlab.ffi.Ffi;
import memlab.ffi.HeapCheck;
import memlab.gen.Cx;
import org.teavm.interop.Address;

import static memlab.Lab.report;

public final class PriorArt {
    private PriorArt() {}

    static void heapCheck() {
        HeapCheck.violations = 0;
        Cx.strcmp(Old.cstr("a"), Old.cstr("b"));
        var buf = new byte[36];
        Cx.getFileAttributesExA(Old.cstr("C:\\Windows\\notepad.exe"), 0, Address.ofData(buf));
        report("heapcheck.old-shapes", "4", "" + HeapCheck.violations);
        HeapCheck.violations = 0;
        Ffi.run(m -> Cx.strcmp(m.c("a"), m.c("b")));
        Ffi.run(m -> Cx.getFileAttributesExA(m.c("C:\\Windows\\notepad.exe"), 0, m.buf(36)));
        report("heapcheck.ffi", "0", "" + HeapCheck.violations);
    }

    static void generatedStringOverloads() {
        report("gen.strcmp", "0", "" + Cx.strcmp("apple", "apple"));
        report("gen.strlen", "5", "" + Cx.strlen("apple"));
        var before = F.mark();
        for (var i = 0; i < 100_000; i++) Cx.strlen("C:\\some\\long\\path\\to\\a\\file-" + i);
        report("gen.frees-immediately", "" + before, "" + F.mark());
    }
}
