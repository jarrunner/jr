<#
Builds teavm-poc's full jr port (pocapp.jr.Jr) in place with the same pipeline as teavm-poc/README.md, but writes the
two exes to memsafe-lab\out\ so teavm-poc\dist is untouched until the result is verified.
#>
$ErrorActionPreference = "Continue"
$poc = Resolve-Path "$PSScriptRoot\..\teavm-poc"
$out = "$PSScriptRoot\out"
New-Item -ItemType Directory -Force $out | Out-Null
Push-Location $poc
try {
    & mvn -q compile
    if ($LASTEXITCODE -ne 0) { throw "mvn compile failed" }
    & mvn -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
    $cp = (Get-Content -Raw target\cp.txt).Trim()
    $m2 = "$env:USERPROFILE\.m2\repository\org\teavm"
    $v = "0.15.0"
    $classlib = (@("teavm-classlib", "teavm-interop", "teavm-platform", "teavm-core") |
        ForEach-Object { "$m2\$_\$v\$_-$v.jar" }) -join ";"
    if (Test-Path target\c) { Remove-Item -Recurse -Force target\c }
    & java -cp "$cp;target\classes" pocapp.BuildDriver target\classes target\c pocapp.jr.Jr $classlib *> "$out\teavm.log"
    if ($LASTEXITCODE -ne 0) { throw "TeaVM BuildDriver failed - see $out\teavm.log" }
    & powershell -NoProfile -File postprocess.ps1 -Dir target\c
    if ($LASTEXITCODE -ne 0) { throw "postprocess failed" }
    $libs = @("-lwinhttp", "-lbcrypt", "-lcomctl32")
    & x86_64-w64-mingw32-clang -O2 -o "$out\jr-teavm.exe" target\c\all.c @libs *> "$out\clang-O2.log"
    if ($LASTEXITCODE -ne 0) { throw "clang -O2 failed - see $out\clang-O2.log" }
    & x86_64-w64-mingw32-clang -Oz -flto -ffunction-sections -fdata-sections "-Wl,--gc-sections" -s -o "$out\jr-teavm-opt.exe" target\c\all.c @libs *> "$out\clang-opt.log"
    if ($LASTEXITCODE -ne 0) { throw "clang -Oz failed - see $out\clang-opt.log" }
    Get-Item "$out\jr-teavm.exe", "$out\jr-teavm-opt.exe" | ForEach-Object { "{0} {1:N0} bytes" -f $_.Name, $_.Length }
    "clang warnings (-O2): " + (Select-String -Path "$out\clang-O2.log" -Pattern "warning:" | Measure-Object).Count
    "clang errors (-O2): " + (Select-String -Path "$out\clang-O2.log" -Pattern "error:" | Measure-Object).Count
} finally {
    Pop-Location
}
