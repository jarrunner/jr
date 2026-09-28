<#
Builds the full TeaVM port of jr (pocapp.jr.Jr) from a COPY of ../../teavm-poc, so teavm-poc itself is never
touched. -Variant hand keeps its hand-maintained WinApi.java/WinOffsets.java (the baseline); -Variant gen swaps
in the generated ones from .\gen plus the two caller edits the corrected types require. Same pipeline as
teavm-poc/README.md: mvn compile -> BuildDriver (TeaVM C backend) -> postprocess.ps1 -> llvm-mingw clang.
Output: app-<variant>\jr-<variant>.exe (-O2) and jr-<variant>-opt.exe (size-optimised), clang log beside them.
#>
param([ValidateSet("hand", "gen")][string]$Variant = "gen")
$ErrorActionPreference = "Continue"  # PS 5.1 turns native stderr into errors; exit codes are checked instead
$here = $PSScriptRoot
$poc = Resolve-Path "$here\..\..\teavm-poc"
$app = Join-Path $here "app-$Variant"

if (Test-Path $app) { Remove-Item -Recurse -Force $app }
New-Item -ItemType Directory $app | Out-Null
Copy-Item "$poc\pom.xml", "$poc\postprocess.ps1" $app
Copy-Item -Recurse "$poc\src" "$app\src"

$jr = "$app\src\main\java\pocapp\jr"
$utf8 = New-Object System.Text.UTF8Encoding($false)
if ($Variant -eq "gen") {
    Copy-Item "$here\gen\pocapp\jr\WinApi.java", "$here\gen\pocapp\jr\WinOffsets.java" $jr -Force
    # INTERNET_PORT is a WORD: the generated binding says short, where the hand one said int.
    $http = [IO.File]::ReadAllText("$jr\Http.java")
    $http = $http.Replace('https ? 443 : 80, 0)', '(short) (https ? 443 : 80), 0)')
    [IO.File]::WriteAllText("$jr\Http.java", $http, $utf8)
    # The three BCrypt property names come from bcrypt.h now, not from string literals typed by hand.
    $sha = [IO.File]::ReadAllText("$jr\Sha256.java")
    $sha = $sha.Replace('Wstr.of("SHA256")', 'Wstr.of(WinApi.BCRYPT_SHA256_ALGORITHM)')
    $sha = $sha.Replace('Wstr.of("ObjectLength")', 'Wstr.of(WinApi.BCRYPT_OBJECT_LENGTH)')
    $sha = $sha.Replace('Wstr.of("HashDigestLength")', 'Wstr.of(WinApi.BCRYPT_HASH_LENGTH)')
    [IO.File]::WriteAllText("$jr\Sha256.java", $sha, $utf8)
}

Push-Location $app
try {
    & mvn -q compile
    if ($LASTEXITCODE -ne 0) { throw "mvn compile failed" }
    & mvn -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
    if ($LASTEXITCODE -ne 0) { throw "build-classpath failed" }
    $cp = (Get-Content -Raw cp.txt).Trim()
    $m2 = "$env:USERPROFILE\.m2\repository\org\teavm"
    $v = "0.15.0"
    $classlib = (@("teavm-classlib", "teavm-interop", "teavm-platform", "teavm-core") |
        ForEach-Object { "$m2\$_\$v\$_-$v.jar" }) -join ";"
    & java -cp "$cp;target\classes" pocapp.BuildDriver target\classes target\c pocapp.jr.Jr $classlib *> teavm.log
    if ($LASTEXITCODE -ne 0) { throw "TeaVM BuildDriver failed - see $app\teavm.log" }
    & powershell -NoProfile -File postprocess.ps1 -Dir target\c
    if ($LASTEXITCODE -ne 0) { throw "postprocess failed" }
    $libs = @("-lwinhttp", "-lbcrypt", "-lcomctl32")
    & x86_64-w64-mingw32-clang -O2 -o "jr-$Variant.exe" target\c\all.c @libs *> clang-O2.log
    if ($LASTEXITCODE -ne 0) { throw "clang -O2 failed - see $app\clang-O2.log" }
    & x86_64-w64-mingw32-clang -Oz -flto -ffunction-sections -fdata-sections "-Wl,--gc-sections" -s -o "jr-$Variant-opt.exe" target\c\all.c @libs *> clang-opt.log
    if ($LASTEXITCODE -ne 0) { throw "clang -Oz failed - see $app\clang-opt.log" }
    Get-Item "jr-$Variant.exe", "jr-$Variant-opt.exe" | ForEach-Object { "{0} {1:N0} bytes" -f $_.Name, $_.Length }
    "clang warnings (-O2): " + (Select-String -Path clang-O2.log -Pattern "warning:" | Measure-Object).Count
} finally {
    Pop-Location
}
