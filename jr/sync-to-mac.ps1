# Mirrors this Windows checkout of jarrunner/jr onto the Mac test machine (ssh alias "mac", see
# ~/.ssh/config): the Mac clone is reset to origin/main, then this machine's unpushed commits and
# uncommitted changes go over as one patch (git diff emits LF, so no CRLF reaches the Mac), and
# untracked files are copied as they are.
# Usage: powershell -File sync-to-mac.ps1 [-Build] [-Fetch]
#   -Build runs build-macos.sh there; -Fetch copies the binaries back to jr/dist-macos for jr-maven-plugin.
param([switch]$Build, [switch]$Fetch, [string]$Remote = 'mac', [string]$Dir = 'code/jr')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$patch = Join-Path $env:TEMP 'jr-to-mac.patch'
git -C $root diff --binary origin/main --output=$patch
if ($LASTEXITCODE) { throw 'git diff failed' }
ssh $Remote "set -e; [ -d $Dir/.git ] || git clone -q https://github.com/jarrunner/jr.git $Dir; cd $Dir; git fetch -q origin; git reset -q --hard origin/main; git clean -fdq -e 'build-macos_*' -e cp-macos.txt"
if ($LASTEXITCODE) { throw 'reset on the Mac failed' }
scp -q $patch "${Remote}:$Dir/.sync.patch"
ssh $Remote "cd $Dir && git apply --whitespace=nowarn .sync.patch && rm .sync.patch"
if ($LASTEXITCODE) { throw 'git apply on the Mac failed' }
# the git.exe identity proxy prints an ACCOUNT(...) banner on stdout
$untracked = git -C $root ls-files --others --exclude-standard | Where-Object { $_ -notmatch '^ACCOUNT\(' }
foreach ($f in $untracked) {
    $d = [IO.Path]::GetDirectoryName($f) -replace '\\', '/'
    if ($d) { ssh $Remote "mkdir -p $Dir/$d" }
    scp -q (Join-Path $root $f) "${Remote}:$Dir/$f"
}
Write-Host "synced: $((Get-Item $patch).Length) byte patch, $(@($untracked).Count) untracked file(s)"
if ($Build) {
    ssh $Remote "cd $Dir/jr && sh build-macos.sh"
    if ($LASTEXITCODE) { throw 'build-macos.sh failed on the Mac' }
}
# -Fetch: bring the built binaries back to jr/dist-macos, which jr-maven-plugin bundles (PRP-36)
if ($Fetch) {
    $dist = Join-Path $PSScriptRoot 'dist-macos'
    New-Item -ItemType Directory -Force $dist | Out-Null
    scp -q "${Remote}:$Dir/jr/build-macos_universal/jr-macos" (Join-Path $dist 'jr-macos')
    scp -q "${Remote}:$Dir/jr/build-macos_arm64/jr-posix" (Join-Path $dist 'jr-macos-arm64')
    scp -q "${Remote}:$Dir/jr/build-macos_x64/jr-posix" (Join-Path $dist 'jr-macos-x86_64')
    Get-ChildItem $dist | ForEach-Object { '{0,-18} {1,9:N0}' -f $_.Name, $_.Length }
}
