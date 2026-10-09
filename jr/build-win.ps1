<#
Full local build recipe for the TeaVM/Java jr, in one script - compiles the Java, generates C via
BuildDriver (TeaVM from the jarrunner/teavm fork, so the C needs no patching; PRP-37), links it for each requested Windows
architecture, and bakes in the default icon (PRP-24). Mirrors README.md's manual step-by-step;
read that first if this script needs changing; it explains the WHY behind each step.

TeaVM's C generation is architecture-agnostic (target\c\all.c is plain C, not tied to a triple), so
it runs once per build regardless of how many architectures are requested - only the final clang
link step differs per architecture.

Two variants per architecture, both built by default, matching the two pre-existing dist\ builds
this superseded (jr-teavm.exe / jr-teavm-opt.exe from README's own history):
  - the size-optimized build (-Oz/-flto/...) is the one anyone should actually ship - this is what
    gets the plain name and the icon.
  - the plain -O2 ("fat") build is kept alongside for size/debugging comparison only, suffixed
    "-fat" so it's never mistaken for the shipping artifact.

Icon stamping works on ANY target architecture from the one x86_64 host build: BeginUpdateResourceW/
UpdateResourceW/EndUpdateResourceW edit a PE file's resource directory as data, they do not execute
the target, so the running x86_64 jr.exe can stamp an aarch64 jr.exe exactly the same way it stamps
its own architecture - verified 2026-09-28 (arm64 output's resources read back correctly with
-Xjr:list-resources from the x86_64 editor). A running exe can never edit its own file (Windows
denies the write - "Cannot open for writing (error 32)", a sharing violation, not a bug), so a
throwaway copy of the x86_64 build always does the editing, whichever architecture is the target.

Usage: powershell -File build-win.ps1 [-Arch x86_64,arm64] [-NoIcon] [-DistDir dist] [-Checks]
  -Arch     comma-separated list of jarrunner.jr architectures to build (default: both).
            Maps to clang target triples: x86_64 -> x86_64-w64-mingw32, arm64 -> aarch64-w64-mingw32.
  -NoIcon   skip icon stamping entirely (e.g. while icon/jr-icon.ico is mid-edit elsewhere).
  -DistDir  where the named, ready-to-ship exes land (default: dist, next to the existing
            jr-teavm.exe/jr-teavm-opt.exe kept there historically - this script does not touch those).
  -Checks   a checks build (PRP-35 phase 3): compiles src/checks-on, so every Buf access is checked against its
            size; default DistDir dist-checks. Never ship it. The release build compiles src/checks-off, whose
            constant false makes javac drop the checks. Every build compiles clean, so the two never mix.

Output naming: dist\jr-windows-<arch>.exe (optimized, the one to ship) and
dist\jr-windows-<arch>-fat.exe (plain -O2, kept aside). Not signed. Releases are built by CI
(.github/workflows/release.yml), which runs this same script on a clean Windows machine.
#>
param(
    [string[]]$Arch = @('x86_64', 'arm64'),
    [switch]$NoIcon,
    [string]$DistDir = '',
    [switch]$Checks
)
if (-not $DistDir) { $DistDir = if ($Checks) { 'dist-checks' } else { 'dist' } }

Set-Location $PSScriptRoot
# Deliberately NOT $ErrorActionPreference = 'Stop': under PowerShell 5.1, a native command's routine
# stderr output (clang's own warnings, of which a clean build still prints hundreds) gets wrapped as
# a non-terminating error, and 'Stop' promotes that into an immediate abort before $LASTEXITCODE is
# even checked - killing the build partway through a successful compile. Every step below already
# checks $LASTEXITCODE explicitly, which is the correct signal for a native command either way.

$triples = @{ 'x86_64' = 'x86_64-w64-mingw32'; 'arm64' = 'aarch64-w64-mingw32' }
foreach ($a in $Arch) {
    if (-not $triples.ContainsKey($a)) { throw "unknown -Arch '$a' (expected x86_64 and/or arm64)" }
}

Write-Host "[1/4] mvn clean compile$(if ($Checks) { ' (checks build)' })"
if ($Checks) { mvn -q clean compile -Dchecks } else { mvn -q clean compile }
if ($LASTEXITCODE -ne 0) { throw 'mvn compile failed' }

Write-Host '[2/4] resolving classpath'
# --% (stop-parsing) is load-bearing: without it PowerShell 5.1 mangles -Dmdep.outputFile=cp.txt
# (treats the leading -D specially and splits the token), and mvn then sees a bogus lifecycle
# phase - verified 2026-09-28.
mvn -q --% dependency:build-classpath -Dmdep.outputFile=cp.txt
if ($LASTEXITCODE -ne 0) { throw 'mvn dependency:build-classpath failed' }
$fullCp = (Get-Content cp.txt -Raw).Trim()
$teavmJars = $fullCp -split ';' | Where-Object { $_ -match 'teavm-(classlib|interop|platform|core)-' }
if ($teavmJars.Count -ne 4) { throw "expected 4 teavm jars on the classpath (classlib/interop/platform/core), found $($teavmJars.Count)" }
$extraClasspath = $teavmJars -join ';'

Write-Host '[3/4] generating C (TeaVM, once - architecture-agnostic)'
if (Test-Path target/c) { Remove-Item -Recurse -Force target/c }
java -cp "$fullCp;target/classes" jarrunner.jr.build.BuildDriver target/classes target/c jarrunner.jr.Jr $extraClasspath
if ($LASTEXITCODE -ne 0) { throw 'BuildDriver failed' }

New-Item -ItemType Directory -Force $DistDir | Out-Null
$libs = @('-lwinhttp', '-lbcrypt', '-lcomctl32', '-lversion', '-lcrypt32', '-lmssign32', '-lgdi32')

# The x86_64 optimized build doubles as the icon editor for every architecture's output (see the
# file header - resource editing does not execute the target, so cross-arch stamping is fine, but
# the EDITOR itself must run on THIS host, which is x86_64).
$editorSourceArch = 'x86_64'
$builtOptimized = @{}

Write-Host '[4/4] linking'
foreach ($a in $Arch) {
    $triple = $triples[$a]
    $clang = "$triple-clang"

    $optOut = Join-Path $DistDir "jr-windows-$a.exe"
    $fatOut = Join-Path $DistDir "jr-windows-$a-fat.exe"

    Write-Host "  $a (optimized) -> $optOut"
    # Every flag as its own quoted array element, splatted with @ - PowerShell's tokenizer treats
    # an unquoted comma (as in -Wl,--gc-sections) as the comma OPERATOR, silently building an
    # array instead of passing one literal token, which is what broke this the first time round.
    $optFlags = @('-Oz', '-flto', '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections', '-Wl,--no-insert-timestamp', '-s',
        '-I', 'bindings/windows', '-Wno-error=incompatible-function-pointer-types', '-o', $optOut, 'target/c/all.c') + $libs
    & $clang @optFlags
    if ($LASTEXITCODE -ne 0) { throw "clang link failed for $a (optimized)" }
    $builtOptimized[$a] = $optOut

    Write-Host "  $a (fat, -O2, kept aside) -> $fatOut"
    $fatFlags = @('-O2', '-Wl,--no-insert-timestamp', '-I', 'bindings/windows', '-Wno-error=incompatible-function-pointer-types', '-o', $fatOut, 'target/c/all.c') + $libs
    & $clang @fatFlags
    if ($LASTEXITCODE -ne 0) { throw "clang link failed for $a (fat)" }
}

if ($NoIcon) {
    Write-Host 'Done (icon skipped, -NoIcon)'
    exit 0
}

$iconFile = '../icon/jr-icon.ico'
if (-not (Test-Path $iconFile)) {
    Write-Warning "$iconFile not found - skipping icon stamping (build is otherwise complete)"
    exit 0
}

# PRP-31: the same manifest the maven plugin stamps (Common Controls 6, per-monitor DPI, supported OS): without it
# jr.exe ran as a legacy program, with bitmap-stretched, old-style windows on a scaled display.
$manifestFile = '../jr-maven-plugin/src/main/resources/jarrunner/jr/maven/app.manifest'
$editor = Join-Path $DistDir 'jr-icon-editor.exe'
# An arm64-only run builds no x86_64 editor: use the x86_64 exe already in dist, or stop. (Before PRP-31 this
# copied $null, every stamp failed silently, and the run still ended "Done" with unbranded, manifest-less exes.)
$editorSource = $builtOptimized[$editorSourceArch]
if (-not $editorSource) { $editorSource = Join-Path $DistDir "jr-windows-$editorSourceArch.exe" }
if (-not (Test-Path $editorSource)) { throw "no $editorSourceArch jr exe to stamp with: build -Arch $editorSourceArch first (or together)" }
Copy-Item $editorSource $editor -Force
foreach ($a in $Arch) {
    foreach ($out in @((Join-Path $DistDir "jr-windows-$a.exe"), (Join-Path $DistDir "jr-windows-$a-fat.exe"))) {
        Write-Host "  stamping icon on $out"
        # A freshly-linked exe is sometimes still momentarily locked (Defender's on-write scan,
        # observed 2026-09-28 - "Cannot open for writing (error 32)" that a bare retry immediately
        # cleared), so this is a real transient, not a bug - retry a few times before giving up.
        $attempt = 0
        do {
            $attempt++
            & $editor "-Xjr:edit=$out" "-Xjr:icon=$iconFile" "-Xjr:manifest=$manifestFile"
            if ($LASTEXITCODE -eq 0) { break }
            if ($attempt -ge 5) { throw "icon stamping failed for $out after $attempt attempts" }
            Start-Sleep -Milliseconds 500
        } while ($true)
    }
}
Remove-Item $editor -Force

Write-Host "Done: $DistDir\jr-windows-<arch>.exe (ship these), *-fat.exe kept aside for comparison"
