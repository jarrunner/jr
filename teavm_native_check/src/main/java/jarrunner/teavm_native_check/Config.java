package jarrunner.teavm_native_check;

import java.util.*;

/** Plugin arguments, each {@code key=value}; lists are comma separated. Nothing here is specific to one project.
 *  <ul>
 *  <li>{@code pointer} the raw pointer type (default {@code org.teavm.interop.Address}); {@code wrappers} types that
 *      carry such a pointer (a bounds-checked buffer): the same rules on keeping, returning and arraying them, but their
 *      methods are not raw access, and a wrapper class may hold its own pointer field
 *  <li>{@code scope} the method(s) whose lambda argument is a scope, as {@code pkg.Class.method}
 *  <li>{@code raw} classes allowed raw pointer access without {@code @Unsafe} (nested classes included)
 *  <li>{@code unsafe}, {@code handle}, {@code scoped}, {@code async}, {@code escapes}, {@code returned} annotation names:
 *      a simple name matches an annotation of that name in any package, a qualified name only that one
 *  <li>{@code ctype} the annotation naming the C struct a pointer points to (default CType); {@code alloc} allocators
 *      whose {@code X.SIZE} argument gives the struct, X being a class carrying that annotation
 *  <li>{@code allocators} methods returning memory owned by the enclosing scope (NC7: it must not reach an
 *      {@code @Escapes} parameter); defaults to {@code alloc}
 *  <li>{@code trust} methods that take a caller's word for something every later check relies on (e.g. a buffer's
 *      size, {@code pkg.Buf.wrap}): calling them counts as raw access, allowed only in raw classes and
 *      {@code @Unsafe} code
 *  <li>{@code suspend} methods that can suspend a TeaVM fiber, as {@code pkg.Class.method}
 *  <li>{@code acquires}, {@code owns} annotation names for NC8 (resources): a method whose result must be closed, naming
 *      the calls that close it; a parameter that takes over closing what it is given
 *  <li>{@code tracked} exception types a method must declare when it lets them out (NC8-throws), so the resource rule
 *      sees every path one method at a time; {@code takes} methods that take over a resource passed to them, so the caller
 *      no longer closes it (a hand-over helper for what the C runtime adopts, or what stays open until the process exits)
 *  <li>{@code foreign}, {@code same} annotation names for NC9 (which OS thread calls a callback); {@code callbacks} the
 *      methods that turn a named Java method into a C function pointer (default {@code org.teavm.interop.Function.get})
 *  <li>{@code mode} {@code error} (default) fails the build, {@code warn} only reports
 *  </ul> */
final class Config {
    final String pointer, unsafe, handle, scoped, async, ctype, escapes, returned, acquires, owns, foreign, same;
    final Set<String> scope, raw, suspend, alloc, allocators, wrappers, trust, tracked, takes, callbackFactory;
    final boolean warn;

    private Config(Map<String, String> m) {
        pointer = m.getOrDefault("pointer", "org.teavm.interop.Address");
        unsafe = m.getOrDefault("unsafe", "Unsafe");
        handle = m.getOrDefault("handle", "Handle");
        scoped = m.getOrDefault("scoped", "Scoped");
        async = m.getOrDefault("async", "org.teavm.interop.Async");
        ctype = m.getOrDefault("ctype", "CType");
        escapes = m.getOrDefault("escapes", "Escapes");
        returned = m.getOrDefault("returned", "Returned");
        acquires = m.getOrDefault("acquires", "Acquires");
        owns = m.getOrDefault("owns", "Owns");
        foreign = m.getOrDefault("foreign", "ForeignThread");
        same = m.getOrDefault("same", "SameThread");
        callbackFactory = list(m.getOrDefault("callbacks", "org.teavm.interop.Function.get"));
        tracked = list(m.getOrDefault("tracked", ""));
        takes = list(m.getOrDefault("takes", ""));
        scope = list(m.getOrDefault("scope", ""));
        raw = list(m.getOrDefault("raw", ""));
        alloc = list(m.getOrDefault("alloc", ""));
        allocators = list(m.getOrDefault("allocators", m.getOrDefault("alloc", "")));
        wrappers = list(m.getOrDefault("wrappers", ""));
        trust = list(m.getOrDefault("trust", ""));
        suspend = list(m.getOrDefault("suspend",
                "java.lang.Thread.sleep,java.lang.Thread.join,java.lang.Thread.yield,java.lang.Object.wait"));
        warn = switch (m.getOrDefault("mode", "error")) {
            case "error" -> false;
            case "warn" -> true;
            default -> throw new IllegalArgumentException("NativeCheck: mode must be error or warn");
        };
    }

    static Config parse(String... args) {
        var known = Set.of("pointer", "unsafe", "handle", "scoped", "async", "ctype", "escapes", "returned", "acquires", "owns", "tracked", "takes", "foreign", "same", "callbacks", "scope", "raw", "alloc",
                "allocators", "wrappers", "trust", "suspend", "mode");
        var m = new HashMap<String, String>();
        for (var a : args) {
            var eq = a.indexOf('=');
            if (eq < 0 || !known.contains(a.substring(0, eq)))
                throw new IllegalArgumentException("NativeCheck: unknown argument '" + a + "', expected one of " + known);
            m.put(a.substring(0, eq), a.substring(eq + 1));
        }
        return new Config(m);
    }

    private static Set<String> list(String s) {
        var r = new LinkedHashSet<String>();
        for (var p : s.split(",")) if (!p.isBlank()) r.add(p.trim());
        return r;
    }
}
