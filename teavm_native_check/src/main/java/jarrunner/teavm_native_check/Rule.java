package jarrunner.teavm_native_check;

/** Every diagnostic, worded for a Java developer who has never seen the project: what, why, what to do instead. */
enum Rule {
    FIELD("NC1-field", "A pointer is stored in field '%s'.",
            "A field outlives every memScoped block, so it can end up pointing at memory that has already been freed and reused. Nothing in Java warns you when that happens; the program just reads garbage or crashes later, somewhere else.",
            "Keep the pointer in a local variable inside the block that allocated it. If the field holds an OS handle (a window, a file, a module, a FILE*), which the operating system owns rather than the scope, mark the field @Handle."),
    RETURN("NC1-return", "A pointer is returned out of a memScoped block.",
            "Everything allocated inside the block is freed when the block ends, so the caller receives a pointer to freed memory.",
            "Finish the work that needs the pointer inside the block and return a Java value (a String, an int, a byte[]) instead. Memory that must outlive the block can be allocated outside any memScoped; it then lives for the whole program."),
    ASSIGN("NC1-assign", "A pointer is stored, inside a memScoped block, into '%s', which is declared outside the block.",
            "The block frees its memory when it ends, but the outer variable keeps the pointer, so later code can use freed memory.",
            "Use the pointer only inside the block. If the target is a field holding an OS handle, mark that field @Handle."),
    OF_DATA("NC2-ofData", "Address.ofData(...) is not allowed.",
            "It points at a Java array on the garbage-collected heap. The collector can move or free that array while native code still holds the pointer; this use-after-free was measured in jr (PRP-18).",
            "Allocate native memory with the project's allocator (for example alloc(n) inside memScoped) and copy the data in."),
    OF_OBJECT("NC2-ofObject", "Address.ofObject(...) is not allowed.",
            "It points at a Java object on the garbage-collected heap, which the collector can move or free while native code still holds the pointer.",
            "Allocate native memory with the project's allocator and copy the fields you need into it."),
    ARRAY("NC2-array", "An array of pointers ('%s') is not allowed.",
            "TeaVM's C backend treats a pointer as a raw machine word, not an object; storing it in an array silently corrupts it (seen in jr), and an array also lets a pointer outlive its scope unseen.",
            "Use separate local variables, or a block of native memory written with putAddress inside @Unsafe code."),
    SCOPED("NC3-scoped", "'%s' is marked @Scoped and may only be called inside a memScoped block.",
            "It allocates memory that piles up (a large buffer, or one allocation per loop or per line). Outside a scope that memory is never freed, for as long as the program runs.",
            "Wrap the call, and every use of what it returns, in memScoped(() -> { ... }). A method that only passes the obligation on to its caller can itself be marked @Scoped."),
    RAW("NC4-raw", "Raw pointer access ('%s') outside code marked @Unsafe.",
            "Pointer arithmetic and raw reads and writes are where memory bugs live. Keeping them in a few marked places makes the code that needs careful review a short list anyone can grep for.",
            "Use the generated struct accessors or the allocation helpers instead. If raw access is really needed, put it in a method marked @Unsafe(\"one-line reason\")."),
    REASON("NC4-reason", "@Unsafe on '%s' has no reason.",
            "@Unsafe marks code a reviewer must read with care. Without a reason the reviewer has to rediscover why the raw access is there before they can judge whether it is right.",
            "Write one line saying why the raw access is needed: @Unsafe(\"walks the PE resource table by offset\")."),
    FORGE("NC4-forge", "A pointer is made from %s outside code marked @Unsafe.",
            "Nothing checks that the number or object really is what the code then reads, writes or calls: a wrong value crashes, or reads memory that belongs to something else.",
            "Use a generated binding or a named helper that says what the value is (an OS handle, a resource id). If the conversion is really needed, put it in a method marked @Unsafe(\"what the value is trusted to be\")."),
    BORROW_RETURN("NC7-return", "Pointer parameter '%s' is returned, but it is only borrowed.",
            "A pointer parameter is borrowed by default: the caller still owns that memory and may free it (its memScoped can end) as soon as this call returns, while whoever keeps the returned pointer still holds it.",
            "If the method returns a pointer into its argument (a field, an offset), mark the parameter @Returned: callers then treat the result as part of what they passed. If it keeps the pointer longer, mark it @Escapes. Or return a Java value read from it."),
    BORROW_STORE("NC7-store", "Pointer parameter '%s' is stored in '%s', but it is only borrowed.",
            "The caller may free that memory as soon as this call returns; the stored copy then points at freed memory.",
            "Mark the parameter @Escapes, so every caller sees that the pointer is kept. Or store a Java copy of what it points to."),
    BORROW_PASS("NC7-pass", "Pointer parameter '%s' is passed to '%s', which keeps it (@Escapes), but here it is only borrowed.",
            "The callee keeps the pointer after the call, but this method does not own the memory and cannot promise it stays alive.",
            "Mark this method's parameter @Escapes too, which passes the promise up to its own callers."),
    SCOPED_ESCAPE("NC7-scoped", "Memory allocated in this memScoped block is passed to '%s', which keeps it (@Escapes).",
            "The block frees the memory when it ends, but the callee keeps the pointer and uses it later.",
            "Allocate it outside any memScoped (it then lives for the whole program), or give the callee memory it can own."),
    OVERRIDE("NC7-override", "Parameter '%s' is @Escapes here, but not in the method this overrides.",
            "Callers through the overridden method pass borrowed pointers, believing they are not kept.",
            "Mark the parameter @Escapes in the overridden method as well, or do not keep the pointer in this override."),
    TYPE("NC6-type", "A pointer to '%s' is passed where '%s' is expected.",
            "The function reads and writes the fields of its own struct at fixed offsets. Given another struct it reads the wrong bytes, or runs past the end of the memory.",
            "Pass a pointer to the struct the function expects. For a struct nested inside another, use the generated field accessor, for example WIN32_FILE_ATTRIBUTE_DATA.ftLastWriteTime(p) for its FILETIME."),
    LEAK("NC8-leak", "'%s' (opened by %s on line %d) is still open when %s.",
            "An OS resource (a file, a handle, a module, a connection) that is never closed stays open until the process exits: the file stays locked, the handle count grows, and a long-running program eventually runs out.",
            "Close it with %5$s on this path too, or once in a finally block that covers every path. If it is meant to outlive this method, return it (and mark the method @Acquires), store it in a field, or pass it to a parameter marked @Owns. If it must stay open until the process exits (a library the program keeps using), pass it to the project's hand-over method (the plugin's takes= list), which says so in the code."),
    LOST("NC8-lost", "The resource opened by %s is lost: %s.",
            "Nothing holds the resource any more, so nothing can close it; it stays open until the process exits.",
            "Keep it in a local variable and close it with %3$s on every path, or pass it to a parameter marked @Owns."),
    OVERWRITE("NC8-overwrite", "'%s' is reassigned while it may still hold the resource opened by %s on line %d.",
            "The old value is gone, so that resource can no longer be closed.",
            "Close it with %4$s first, or use a new variable for the new value."),
    ACQUIRES("NC8-acquires", "A resource opened by %s is returned, but this method is not marked @Acquires.",
            "Callers cannot see that they now own an open resource, so nothing checks that they close it.",
            "Mark the method @Acquires(\"close1,close2\") naming the calls that close what it returns; its callers are then checked like callers of the OS function."),
    THROWS("NC8-throws", "%s can be thrown out of '%s' here, but the method does not declare it.",
            "The resource rule checks every exception path one method at a time, from each callee's signature. An exception that leaves a method without being declared is a path its callers cannot see, so a resource they hold could leak on it unnoticed.",
            "Add 'throws %1$s' to the method (it costs nothing at run time, even for an unchecked exception), or catch it here."),
    SUSPEND("NC5-suspend", "'%s' can suspend the current thread inside a memScoped block.",
            "TeaVM runs every java.lang.Thread as a fiber on one OS thread, sharing one scope allocator. If this fiber pauses here, another fiber can open and close its own scope and free memory this block is still using.",
            "Move the call out of the memScoped block: finish the native work, leave the block, then sleep, wait, join or synchronize.");

    final String code, what, why, fix;

    Rule(String code, String what, String why, String fix) {
        this.code = code;
        this.what = what;
        this.why = why;
        this.fix = fix;
    }

    String message(Object... args) {
        return "[" + code + "] " + what.formatted(args) + "\n  Why: " + why + "\n  Fix: " + fix.formatted(args);
    }
}
