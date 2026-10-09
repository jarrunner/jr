package jarrunner.jr;

import java.util.function.Supplier;
import org.teavm.interop.Address;

/** Native memory for WinApi calls, used as {@code import static jarrunner.jr.N.*;}. Names follow Kotlin/Native and C.
 *  Everything is allocated off the TeaVM heap (see {@link Arena}), so no GC can free or move it under a pointer, and
 *  allocating Java objects between taking a pointer and the call that uses it is harmless. Memory lives until the
 *  innermost {@link #memScoped} ends, or for the whole program outside any scope. Never store a pointer from here
 *  in a field, and never use one after its scope. Never pass {@code Address.ofData} of a Java array to native code;
 *  use {@link #alloc} instead (prp/18-prp.01 has the measurements behind these rules).
 *
 *  <p>Strings (PRP-34). Every Windows call that carries text a person or the file system produced (paths, the
 *  environment, command lines, registry values, window text) uses the "W" function and {@link #wcstr}/{@link #wstring}:
 *  Windows' native Unicode is UTF-16, and the "A" functions go through the ANSI code page, which loses anything outside
 *  it. Text in files and pipes is UTF-8 ({@link #utf8}, {@link #text}). {@link #ascii} is only for protocol tokens that
 *  have no W form (GetProcAddress names, CRT mode strings, OIDs): never pass it a path or user text. {@link #acp} is
 *  for jli.dll alone, whose JLI_Launch takes char** in the ANSI code page, as java.exe does. */
public final class N {
    private N() {}

    public static final Address NULL = Address.fromInt(0);

    public static <T> T memScoped(Supplier<T> body) {
        var mark = mark();
        try { return body.get(); } finally { release(mark); }
    }

    public static void memScoped(Runnable body) {
        var mark = mark();
        try { body.run(); } finally { release(mark); }
    }

    /** The scope primitives behind memScoped, without a lambda: everything allocated after mark() is freed by
     *  release(mark). The generated String overloads in WinApi/PosixApi use these (PRP-35). */
    public static long mark() { return Arena.mark(); }

    public static void release(long mark) { Arena.reset(mark); }

    /** NUL-terminated ASCII, for protocol tokens only (see the class comment). A char above 0x7F becomes '?'. */
    public static @CType("char") Address ascii(String s) {
        var n = s.length();
        var a = Arena.alloc(n + 1);
        for (var i = 0; i < n; i++) {
            var c = s.charAt(i);
            a.add(i).putByte((byte) (c < 0x80 ? c : '?'));
        }
        a.add(n).putByte((byte) 0);
        return a;
    }

    /** NUL-terminated UTF-16LE string: every "W" API, WinHTTP, BCrypt. */
    public static @CType("wchar_t") Address wcstr(String s) {
        if (s == null) s = "";
        var a = Arena.alloc(s.length() * 2 + 2);
        wcstrInto(a, s);
        return a;
    }

    /** Writes s as NUL-terminated UTF-16LE into memory the caller owns (e.g. GlobalLock'd), which must hold
     *  (s.length() + 1) * 2 bytes. */
    public static void wcstrInto(@CType("wchar_t") Address dst, String s) {
        var n = s.length();
        for (var i = 0; i < n; i++) dst.add(i * 2).putChar(s.charAt(i));
        dst.add(n * 2).putChar((char) 0);
    }

    /** n bytes of native memory copied into a Java array, where every later read is bounds-checked. */
    public static byte[] bytesOf(Address p, int n) {
        var b = new byte[n];
        for (var i = 0; i < n; i++) b[i] = p.add(i).getByte();
        return b;
    }

    /** A native copy of b, for handing a Java-built buffer to the OS. */
    public static Address alloc(byte[] b) {
        var a = Arena.alloc(b.length);
        putBytes(a, b);
        return a;
    }

    /** Copies b into native memory the caller owns, which must hold b.length bytes. */
    public static void putBytes(Address dst, byte[] b) {
        for (var i = 0; i < b.length; i++) dst.add(i).putByte(b[i]);
    }

    /** {@link #text(Address, int)} over bytes already in a Java array. */
    public static String text(byte[] b) {
        var mark = Arena.mark();
        try { return text(alloc(b), b.length); } finally { Arena.reset(mark); }
    }

    /** s as UTF-8 bytes, without the NUL. */
    public static byte[] utf8Bytes(String s) {
        var mark = Arena.mark();
        try {
            var u = utf8(s);
            return bytesOf(u, (int) WinApi.strlen(u));
        } finally { Arena.reset(mark); }
    }

    /** NUL-terminated UTF-8, for text written to files. Unpaired surrogates become U+FFFD. */
    public static @CType("char") Address utf8(String s) {
        return toCodePage(WinApi.CP_UTF8, s);
    }

    /** NUL-terminated ANSI code page string, for jli.dll only (see the class comment). */
    public static @CType("char") Address acp(String s) {
        return toCodePage(WinApi.CP_ACP, s);
    }

    /** True if s survives code page cp unchanged: a process reading it through an "A" API in that code page (java.exe
     *  reads its command line that way) sees exactly s. Always true for UTF-8. cp is WinApi.getACP() for this process
     *  (jli.dll in-process) and {@link #systemAcp} for a child such as java.exe, which may differ: an exe manifest can
     *  make this process UTF-8 while java.exe stays on the system code page. */
    public static boolean fitsCodePage(String s, int cp) {
        if (cp == WinApi.CP_UTF8) return true; // and lpUsedDefaultChar must be NULL for UTF-8
        return memScoped(() -> {
            var used = intVar();
            var w = wcstr(s);
            var n = WinApi.wideCharToMultiByte(cp, 0, w, -1, NULL, 0, NULL, used);
            if (used.getInt() != 0) return false;
            // A best-fit mapping (e.g. U+0101 -> 'a') is not flagged as a default char, so also check the round trip.
            var b = alloc(n);
            WinApi.wideCharToMultiByte(cp, 0, w, -1, b, n, NULL, NULL);
            return s.equals(fromCodePage(cp, 0, b, -1));
        });
    }

    /** The code page other processes get: the system's ANSI code page, which this process's own (WinApi.getACP) is
     *  not when the exe manifest sets activeCodePage. Read from where Windows keeps it, so the system-wide "Beta: Use
     *  Unicode UTF-8" setting is seen too. */
    public static int systemAcp() {
        if (systemAcp == 0) {
            var v = JavaSources.registryValue(WinApi.HKEY_LOCAL_MACHINE, "SYSTEM\\CurrentControlSet\\Control\\Nls\\CodePage", "ACP");
            var cp = v == null ? 0 : Atoi.parse(v);
            systemAcp = cp > 0 ? cp : WinApi.getACP();
        }
        return systemAcp;
    }

    private static int systemAcp;

    private static Address toCodePage(int cp, String s) {
        var w = wcstr(s);
        var n = WinApi.wideCharToMultiByte(cp, 0, w, -1, NULL, 0, NULL, NULL);
        var a = alloc(n > 0 ? n : 1);
        if (n > 0) WinApi.wideCharToMultiByte(cp, 0, w, -1, a, n, NULL, NULL);
        return a;
    }

    /** Zeroed memory: structs, buffers, out-parameters. */
    public static Address alloc(int size) {
        var a = Arena.alloc(size);
        Address.fillZero(a, size);
        return a;
    }

    public static @CType("int16") Address shortVar() { return alloc(2); }
    public static @CType("int32") Address intVar() { return alloc(4); }
    public static @CType("int64") Address longVar() { return alloc(8); }
    public static @CType("pointer") Address ptrVar() { return alloc(Address.sizeOf()); }

    /** The value of an out-parameter from the *Var() functions above: the whole variable, at offset 0, so no pointer
     *  arithmetic happens outside N (PRP-35 rule NC4). */
    public static short shortOf(@CType("int16") Address v) { return v.getShort(); }
    public static int intOf(@CType("int32") Address v) { return v.getInt(); }
    public static long longOf(@CType("int64") Address v) { return v.getLong(); }
    public static Address ptrOf(@CType("pointer") Address v) { return v.getAddress(); }
    public static void setInt(@CType("int32") Address v, int x) { v.putInt(x); }

    /** An OS handle that arrived as a number (a CRT _get_osfhandle result) as the pointer-typed HANDLE the API takes.
     *  Not memory: nothing reads through it. Named so that making a pointer from a number stays rare (NC4-forge). */
    public static Address handle(long value) { return Address.fromLong(value); }

    /** MAKEINTRESOURCE(id): a resource id passed where a name pointer goes. Not memory. */
    public static Address intResource(int id) { return Address.fromLong(id & 0xFFFFL); }

    /** Reads a NUL-terminated UTF-16LE string, or null for a NULL pointer. */
    public static String wstring(@CType("wchar_t") Address p) {
        return p.toLong() == 0 ? null : wstring(p, Integer.MAX_VALUE);
    }

    /** Reads at most max chars of UTF-16LE, stopping at a NUL - also for fixed-size fields such as cFileName[MAX_PATH]. */
    public static String wstring(@CType("wchar_t") Address p, int max) {
        var n = 0;
        while (n < max && p.add(n * 2).getChar() != 0) n++;
        var chars = new char[n];
        for (var i = 0; i < n; i++) chars[i] = p.add(i * 2).getChar();
        return new String(chars);
    }

    /** Reads a NUL-terminated string in the ANSI code page (jli.dll's argv), or null for a NULL pointer. */
    public static String acpString(@CType("char") Address p) {
        return p.toLong() == 0 ? null : fromCodePage(WinApi.CP_ACP, 0, p, -1);
    }

    /** Text from bytes, as read from a file, a resource or the network: a UTF-8 BOM is dropped; valid UTF-8 is
     *  read as UTF-8, and anything else in the ANSI code page (an older Notepad's "ANSI" file, or what a JVM
     *  printed to a redirected stderr), so a stray byte never turns a whole file into U+FFFD. */
    public static String text(Address p, int n) {
        if (n >= 3 && (p.getByte() & 0xFF) == 0xEF && (p.add(1).getByte() & 0xFF) == 0xBB && (p.add(2).getByte() & 0xFF) == 0xBF) {
            p = p.add(3);
            n -= 3;
        }
        if (n <= 0) return "";
        var s = fromCodePage(WinApi.CP_UTF8, WinApi.MB_ERR_INVALID_CHARS, p, n);
        return s != null ? s : fromCodePage(WinApi.CP_ACP, 0, p, n);
    }

    /** {@link #text(Address, int)} over a string holding one byte per char (how Http collects a body). */
    public static String text(String bytes) {
        return memScoped(() -> {
            var n = bytes.length();
            var a = alloc(n + 1);
            for (var i = 0; i < n; i++) a.add(i).putByte((byte) bytes.charAt(i));
            return text(a, n);
        });
    }

    /** n = -1 for NUL-terminated input. Null if flags has MB_ERR_INVALID_CHARS and the bytes are not valid. */
    private static String fromCodePage(int cp, int flags, Address p, int n) {
        var len = WinApi.multiByteToWideChar(cp, flags, p, n, NULL, 0);
        if (len <= 0) return flags == 0 ? "" : null;
        var w = alloc(len * 2 + 2);
        WinApi.multiByteToWideChar(cp, flags, p, n, w, len);
        return wstring(w, n < 0 ? len - 1 : len);
    }

    /** An open OS resource (a handle, a module, a FILE*) is no longer this code's to close: something the checker cannot
     *  see now owns it (the C runtime adopted it as an fd), or it stays open on purpose until the process exits (a
     *  library still in use). Does nothing at run time; it is how the code says so to teavm-native-check (NC8, its
     *  takes= list). Say why at the call. */
    public static void handOver(Address resource) {}
}
