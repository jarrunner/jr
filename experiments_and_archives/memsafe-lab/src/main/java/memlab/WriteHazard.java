package memlab;

import memlab.ffi.Ffi;
import org.teavm.interop.Address;
import org.teavm.interop.Import;

import static memlab.Lab.report;

public final class WriteHazard {
    private WriteHazard() {}

    @Import(name = "GetCurrentDirectoryA") static native int getCurrentDirectoryA(int size, Address buf);

    static byte[][] victims;

    static int corruptedVictims() {
        var n = 0;
        for (var v : victims) for (var b : v) if (b != 'X') { n++; break; }
        return n;
    }

    static void fillVictims() {
        victims = new byte[20_000][];
        for (var i = 0; i < victims.length; i++) {
            var b = new byte[48];
            for (var j = 0; j < b.length; j++) b[j] = 'X';
            victims[i] = b;
        }
    }

    static void old() {
        var buf = new byte[260];
        var addr = Address.ofData(buf);
        Gc.storm();
        fillVictims();
        getCurrentDirectoryA(260, addr);
        report("write.old", "0 victims corrupted", corruptedVictims() + " victims corrupted");
    }

    static void ffi() {
        var cwd = Ffi.s(m -> {
            var buf = m.buf(260);
            Gc.storm();
            fillVictims();
            getCurrentDirectoryA(260, buf);
            return m.str(buf);
        });
        report("write.ffi", "0 victims corrupted", corruptedVictims() + " victims corrupted");
        System.out.println("      cwd=" + cwd);
    }
}
