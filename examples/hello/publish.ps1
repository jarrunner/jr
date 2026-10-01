<#
Publishes the built hello example into a local working copy of the jarrunner.github.io repository:
the jar and the exes under hello/<version>/, and a new first entry in hello/update.json with the
channel pointed at it. Commit and push the working copy afterwards; GitHub Pages serves it over https.

Usage: powershell -File publish.ps1 -Site <path to the jarrunner.github.io working copy> [-Channel stable] [-Notes "..."]
#>
param(
    [Parameter(Mandatory = $true)][string]$Site,
    [string]$Channel = 'stable',
    [string]$Notes = '',
    [string]$BaseUrl = 'https://jarrunner.github.io/hello'
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$version = ([xml](Get-Content pom.xml)).project.version
$jar = "target\hello-$version.jar"
if (-not (Test-Path $jar)) { throw "build first: $jar not found (mvn package)" }

$dir = Join-Path $Site "hello\$version"
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item $jar $dir -Force

$exe = [ordered]@{}
foreach ($arch in @(@('x86_64', 'windows-x86_64'), @('arm64', 'windows-aarch64'))) {
    $file = "hello-windows-$($arch[0]).exe"
    $src = "target\jr\$file"
    if (-not (Test-Path $src)) { continue }
    Copy-Item $src $dir -Force
    $sha = (Get-FileHash $src -Algorithm SHA256).Hash.ToLower()
    $exe[$arch[1]] = [ordered]@{ sha256 = $sha; urls = @("$BaseUrl/$version/$file") }
}

$release = [ordered]@{ version = $version; released = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"); exe = $exe }
if ($Notes) { $release.notes = $Notes }

$updateFile = Join-Path $Site 'hello\update.json'
$releases = @($release)
$channels = [ordered]@{}
if (Test-Path $updateFile) {
    $old = Get-Content $updateFile -Raw | ConvertFrom-Json
    $releases += @($old.releases | Where-Object { $_.version -ne $version })
    $old.channels.PSObject.Properties | ForEach-Object { $channels[$_.Name] = $_.Value }
}
$channels[$Channel] = $version
$update = [ordered]@{ format = 1; app = 'io.github.jarrunner:hello'; channels = $channels; releases = $releases }
# UTF-8 without BOM (jr skips a BOM, but other readers may not)
[IO.File]::WriteAllText($updateFile, ($update | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
Write-Host "published hello $version to $dir; $Channel -> $version"
