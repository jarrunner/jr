memsafe-lab: experiments for PRP-18 (TeaVM Address lifetime across GC, and an API whose shape removes the hazard). Nothing in teavm-poc is modified: the lab compiles teavm-poc's sources read-only (build-helper `add-source`) so its gallery can run the untouched originals side by side with rewritten versions. Findings and the API proposal are in `../prp/18-prp.01.lab-findings-and-api-proposal.md`.

## Build and run

    powershell -File build.ps1                 (memsafe-lab.exe and memsafe-lab-opt.exe)
    memsafe-lab.exe all                        (api, real, gallery, bench, then hazards last)
    memsafe-lab.exe hazards|api|real|gallery|bench|crash

`build.ps1 -Main memlab.probe.SizeFfi10 -Out target\SizeFfi10` builds one of the size probes instead. `crash` runs today's FileInfo shape with a forced GC and may print a wrong value or segfault, depending on the build.

Lint (plain JDK 25, no build): `java lint\AddressLint.java ..\teavm-poc\src\main\java`

## What is where

- `memlab/ffi/` the API under test. `Ffi.i/l/s/ok/run(m -> ...)` is a lambda scope over an off-heap bump arena (`Arena`, malloc'd 64 KB block plus malloc'd overflow chunks, reset on scope exit, poisoned with 0xDD). `Mem` is what the lambda receives: `c`, `w`, `buf`, `int32`, `int64`, `ptr`, `ptrs` (argv), `str`, `wstr`. `F` is the same allocator without a lambda (region style). `Pin` is the zero-copy heap alternative built on the GC's own stack pinning.
- `memlab/Hazards.java`, `WriteHazard.java` today's shapes with a GC forced where one could land: freed, moved, two-strings, native write into freed heap.
- `memlab/ApiDemos.java` the same scenarios through `Ffi`, plus nesting, big buffers, argv, UTF-16 round trip, exception safety, escape poisoning.
- `memlab/gallery/` six real teavm-poc functions rewritten on `Ffi`, compared with the originals with and without forced GC.
- `memlab/probe/` size probes (one site and ten sites, for heap, lambda scope, try/finally frame and region) and a generic-escape probe.
- `lint/` a javac-tree scanner for the two hazard shapes.
