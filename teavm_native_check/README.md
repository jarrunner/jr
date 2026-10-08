# teavm_native_check

A javac plugin that checks native memory use in Java code compiled to C by TeaVM. In TeaVM, ordinary Java is already memory-safe: the C backend inserts null and bounds checks, and the GC owns objects. The unsafe part is `org.teavm.interop.Address`, a bare pointer. This plugin turns the rules for using it safely into compile errors, so they no longer depend on review. It runs only at compile time and adds nothing to the program.

It does not make native code as safe as Rust. It checks one method at a time against the signatures of what it calls, which catches the common mistakes, and leaves a short, greppable list of `@Unsafe` methods for a person to read. What it does not do (yet): close OS resources on every path, check data crossing OS threads, or track NULL.

## What it checks

- **NC1** A pointer must not outlive its scope: no `Address` field (except one marked `@Handle`, holding an OS handle), nothing returned out of a `memScoped` block, nothing stored from inside a block into something declared outside it.
- **NC2** No `Address.ofData` / `Address.ofObject` (pointers into the GC heap, which the GC moves and frees), and no arrays of pointers.
- **NC3** A method marked `@Scoped` (it allocates memory that piles up) is called only inside a scope, or from another `@Scoped` method.
- **NC4** Raw access (`add`, `getInt`, `putByte`, ...), pointers made from numbers (`Address.fromLong`), casts between a pointer and an object, and calls to `trust=` methods happen only in the classes listed as `raw`, or in a method or class marked `@Unsafe("reason")`. The reason is required.
- **NC5** Nothing that can suspend a TeaVM fiber (`Thread.sleep`, `join`, `yield`, `Object.wait`, `@Async` methods, `synchronized`) inside a scope: fibers share one scope allocator.
- **NC6** A pointer to one C type is not passed where another is expected, from `@CType` annotations (jextract_teavm writes them on bindings) and from `alloc(X.SIZE)`.
- **NC7** A pointer parameter is borrowed unless marked: it, or a pointer derived from it, is not returned (unless `@Returned`), stored, or passed on to a parameter that keeps it (unless `@Escapes`). An override may not add `@Escapes`. Memory from a scope does not reach an `@Escapes` parameter.

Every message says what is wrong, why it matters, and what to write instead:

    N.java:12: error: [NC7-store] Pointer parameter 'p' is stored in 'kept', but it is only borrowed.
      Why: The caller may free that memory as soon as this call returns; the stored copy then points at freed memory.
      Fix: Mark the parameter @Escapes, so every caller sees that the pointer is kept. Or store a Java copy of what it points to.

## Using it in a project

1. Install it once: `mvn install` in this folder (JDK 21 or newer; no dependencies beyond the JDK's own `jdk.compiler`).
2. Define the annotations in your own code, with SOURCE retention so they cost nothing: `Handle`, `Unsafe(String value)`, `Scoped`, `CType(String value)`, `Escapes`, `Returned`. The names can be changed through arguments.
3. Run javac with the plugin. With Maven:

        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <configuration>
            <annotationProcessorPaths>
              <path><groupId>io.github.jarrunner</groupId><artifactId>teavm_native_check</artifactId><version>1.0</version></path>
            </annotationProcessorPaths>
            <compilerArgs><arg>-Xplugin:NativeCheck scope=my.N.memScoped alloc=my.N.alloc raw=my.N,my.Bindings</arg></compilerArgs>
          </configuration>
        </plugin>

   With plain javac: `javac -processorpath teavm_native_check-1.0.jar "-Xplugin:NativeCheck scope=..." ...`.

Arguments (`key=value`, lists comma separated), all in `Config.java`:
- `scope`: the method(s) whose lambda argument is a scope (`pkg.N.memScoped`).
- `raw`: classes allowed raw access (your allocator, your generated bindings).
- `alloc` / `allocators`: allocators whose memory belongs to the enclosing scope; with `X.SIZE` they also type the result (NC6).
- `wrappers`: types that carry a pointer, such as a bounds-checked buffer (same rules as a pointer; their methods are not raw).
- `trust`: methods that take the caller's word for something later checks rely on (`pkg.Buf.wrap(pointer, size)`), allowed only where raw access is.
- `pointer`, `unsafe`, `handle`, `scoped`, `async`, `ctype`, `escapes`, `returned`: type and annotation names, if yours differ.
- `suspend`: methods that suspend a fiber (defaults to sleep/join/yield/wait).
- `mode=warn`: report without failing, for a first look at an existing code base.

jr's own configuration is the `<nativecheck>` property in `../jr/pom.xml`, and its usage guide is `../jr/README.md`, section "Native memory".

## Tests

`mvn test` compiles each file in `src/test/resources/cases/` with the plugin, against stub sources, and compares the rules reported with the file's first line (`// expect: NC1-field NC2-ofData`, or `// expect: none`). To add a case, add a file.

## A build note

The pom gives javac an empty `-processorpath`. Without it, javac 25 finds this plugin's own service file in `target/classes` from an earlier build and instantiates it (to ask `autoStart()`) before the plugin is compiled.
