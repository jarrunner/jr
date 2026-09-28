<#
Builds memsafe-lab.exe: mvn compile -> BuildDriver (TeaVM C backend) -> teavm-poc's postprocess.ps1 (read, never
edited) -> llvm-mingw clang. Same pipeline as teavm-poc/README.md. Output: memsafe-lab.exe (-O2) and
memsafe-lab-opt.exe (size-optimised), logs in target\.
#>
param([string]$Main = "memlab.Lab", [string]$Out = "memsafe-lab")
$ErrorActionPreference = "Continue"
Push-Location $PSScriptRoot
try {
    & mvn -q compile
    if ($LASTEXITCODE -ne 0) { throw "mvn compile failed" }
    if (-not (Test-Path target\cp.txt)) {
        & mvn -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
        if ($LASTEXITCODE -ne 0) { throw "build-classpath failed" }
    }
    $cp = (Get-Content -Raw target\cp.txt).Trim()
    $m2 = "$env:USERPROFILE\.m2\repository\org\teavm"
    $v = "0.15.0"
    $classlib = (@("teavm-classlib", "teavm-interop", "teavm-platform", "teavm-core") |
        ForEach-Object { "$m2\$_\$v\$_-$v.jar" }) -join ";"
    if (Test-Path target\c) { Remove-Item -Recurse -Force target\c }
    & java -cp "$cp;target\classes" memlab.BuildDriver target\classes target\c $Main $classlib *> target\teavm.log
    if ($LASTEXITCODE -ne 0) { throw "TeaVM BuildDriver failed - see target\teavm.log" }
    & powershell -NoProfile -File ..\teavm-poc\postprocess.ps1 -Dir target\c
    if ($LASTEXITCODE -ne 0) { throw "postprocess failed" }
    & x86_64-w64-mingw32-clang -O2 -o "$Out.exe" target\c\all.c *> target\clang-O2.log
    if ($LASTEXITCODE -ne 0) { throw "clang -O2 failed - see target\clang-O2.log" }
    & x86_64-w64-mingw32-clang -Oz -flto -ffunction-sections -fdata-sections "-Wl,--gc-sections" -s -o "$Out-opt.exe" target\c\all.c *> target\clang-opt.log
    if ($LASTEXITCODE -ne 0) { throw "clang -Oz failed - see target\clang-opt.log" }
    Get-Item "$Out.exe", "$Out-opt.exe" | ForEach-Object { "{0} {1:N0} bytes" -f $_.Name, $_.Length }
} finally {
    Pop-Location
}
