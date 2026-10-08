# Packing jr with UPX

jr does not need packing: the exe is about 610 KB and we ship it unpacked. If download size matters to you more than anything else, [UPX](https://upx.github.io) shrinks it to about a quarter, and this page has the measurements. They were taken on 2026-10-08 with UPX 5.2.1 on the Windows x64 release build, on a Windows 11 machine with JDK 25.

## Size

| Build | Bytes | Of the original |
|---|---|---|
| jr.exe, unpacked | 625,664 | 100% |
| `upx -9` (NRV) | 177,664 | 28% |
| `upx --best --lzma` | 162,304 | 26% |
| `upx --ultra-brute` | 162,304 | 26% (it chooses LZMA; nothing gained) |

## Speed

Median times over many runs, launched through .NET `System.Diagnostics.Process`. "jr alone" is jr reading a resource listing and exiting, with no Java started. "Full launch" starts a one-class hello-world jar on JDK 25 without an AOT cache.

| | Unpacked | NRV | LZMA |
|---|---|---|---|
| jr alone | 29-34 ms | 38-39 ms | 50-55 ms |
| Full launch | 175-203 ms | 182-193 ms | 193-206 ms |
| First run of a newly copied file | 570-620 ms | 710-720 ms | 730-760 ms |

The packed exe unpacks itself in memory on every launch. That costs about 6 ms (NRV) or 18 ms (LZMA). Next to starting a JVM this is noise, and with a real application (hundreds of ms to seconds) it disappears. The first run of a new file is slower anyway, because Windows Defender scans it, and a packed file takes it about 110-140 ms longer to scan.

## What still works when packed

- Launching, argument passing and the exit code.
- A config embedded in the exe (`-Xjr:resource.RCDATA.JRC=...`) is still found.
- Icon, version information and manifest are kept, and `-Xjr:list-resources` lists them.
- Windows Defender reported nothing on the packed files. Other antivirus products are known to flag UPX-packed, unsigned executables more often. We have not measured that.

## The one rule: pack last

**Never change the resources of a packed exe.** `-Xjr:make`, `-Xjr:edit`, `-Xjr:icon` and the other branding options report success on a packed exe, and the result crashes when it starts. So the order is always:

1. make and brand the exe (`-Xjr:make`, icon, version, embedded config, manifest);
2. pack it with UPX;
3. sign it, if you sign.

Don't use a packed jr.exe as the template for `-Xjr:make` either. Keep the unpacked one for that.

```batch
jr.exe -Xjr:make=myapp.exe -Xjr:icon=myapp.ico -Xjr:resource.RCDATA.JRC=myapp.jrc
upx --best --lzma myapp.exe
```

## How this compares with a C launcher

jr started as a hand-written C launcher ([jarrunner/jr_legacy_c](https://github.com/jarrunner/jr_legacy_c)). When the Java version first had the same features, it was 369,664 bytes against about 77 KB for C: about 4.8 times as large. Most of that difference is TeaVM's runtime, which is a fixed cost. Since then the Java version has gained a lot (choosing among installed Javas, self-update, a JSON config, self-healing launches, doctor and repair, an error dialog) and grown to 625,664 bytes. No C launcher with those features exists to measure. If C had needed between a fifth and half of the bytes the Java version spent on them, it would be roughly 120-190 KB today. The packed jr is 160-175 KB. So, packed, the Java version is roughly as small as a C one would be. That is an estimate, not a measurement.

The old C launcher is much smaller (40-77 KB) and starts about 13 ms faster, but it does far less on each launch.
