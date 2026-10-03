<#
Builds icon/jr-icon.ico from icon/concept-02-jr-monogram.svg (PRP-22's confirmed final icon),
with every size PNG-compressed - not ImageMagick's own icon writer, deliberately.

Why not just `convert -define icon:auto-resize=256,128,64,48,32,16 svg out.ico`: measured
2026-09-28/29, that writer PNG-compresses ONLY the 256x256 entry (which it must - the classic
ICONDIRENTRY width/height byte can't even encode 256, so 256 forces the PNG path) and stores
EVERY OTHER SIZE as a raw, uncompressed 32bpp BMP DIB. A 128x128 entry alone came out at 67,624
bytes - bigger than the compressed 256x256 entry - and the whole file was 110,917 bytes for what
should be a ~15-20 KB icon. No ImageMagick `-define` was found that changes this (tried
icon:compression=png; no effect, byte-identical output). Windows has supported PNG-compressed
icon entries at ANY size since Vista, so this hand-assembles the ICO container instead, with a
real PNG (deflate-compressed) at every size - the format Windows actually expects a modern icon
to use.

Second, separate bug this uncovered: this ImageMagick build is Q16 (16-bit quantum depth), so a
plain `convert svg -resize NxN out.png` with no `-depth` writes 16-bit-per-channel RGBA PNGs -
literally double the raw pixel data of the 8-bit-per-channel PNGs every viewer/OS actually wants,
and PNG's deflate compression cannot make that gap disappear (256x256 came out 31,853 bytes at
16-bit vs 11,092 at `-depth 8`, same image). `-depth 8` is therefore load-bearing on every render
call below, not a cosmetic flag.

Usage: powershell -File build-ico.ps1 (from anywhere - every path here is absolute).
Writes icon\jr-icon.ico. Re-run whenever concept-02-jr-monogram.svg changes.
(Deliberately not $PSScriptRoot-relative: under this harness's PowerShell tool, $PSScriptRoot and
Set-Location both silently resolved to the wrong directory for a child process's own working
directory - external `& $magick` calls kept resolving relative paths one level up, against the
repo root instead of icon\, regardless of what Set-Location/pwd reported inside the script. Never
chased down why; hardcoding the one absolute path this file always lives under sidesteps it.)
#>

$ErrorActionPreference = 'Stop'
$iconDir = 'C:\user\code\jarrunner\jr\icon'
$svg = Join-Path $iconDir 'concept-02-jr-monogram.svg'
if (-not (Test-Path $svg)) { throw "$svg not found" }

$magick = 'C:\user\Apps\cmdtools\ImageMagick-7.1.1-28-portable-Q16-HDRI-x64\convert.exe'
# THE RULE (2026-09-29, settled after trying 6 sizes, then 256/32/16, then landing here): a
# single 256x256, 8-bit-per-channel, PNG-compressed entry is the best size/quality trade-off for
# a Windows icon, full stop - not a compromise. Windows downscales FROM 256 cleanly for every
# smaller context it needs (taskbar, Explorer small/medium icons, Alt-Tab) - that direction is
# just resampling a bigger image down, which looks fine - but there is no equivalent trick for
# upscaling a small one back up for a large-icon view, so 256 is the one size that can never be
# missing. Extra sizes (128/64/48/32/16) each cost a few more KB of exe for a size Windows can
# already synthesize decently on its own - not zero benefit, but not worth it either, per this
# project's own build getting visibly, measurably lighter every time a size was dropped. See
# jr/README.md's "Baking in the default icon" section and jr's own `-Xjr:icon=` --help text for
# the same rule stated for anyone else stamping an icon with this toolkit.
$sizes = @(256)

$pngPaths = @{}
foreach ($s in $sizes) {
    $png = Join-Path $iconDir "jr-icon-$s.tmp.png"
    & $magick -background none -density 384 $svg -resize "${s}x${s}" -depth 8 -define png:compression-level=9 $png
    if ($LASTEXITCODE -ne 0) { throw "rendering ${s}x${s} failed" }
    if (-not (Test-Path $png)) { throw "$png was not created" }
    $pngPaths[$s] = $png
}

# PRP-31 (2026-10-03): the small sizes are DRAWN, not scaled. A 16-20 px caption icon shrunk from the 256 entry
# smears the italic j and r, whatever the resampling (LoadIconWithScaleDown did not visibly help, user-checked).
# So 16/20/24/32 are pixel art: upright 2-unit strokes on a 16-unit grid, every edge on a whole pixel, the same
# purple gradient. ~500-800 bytes each. Windows picks the exact size when it exists (20 is the caption at 125%).
# Shapes in 16-units: x0,y0,x1,y1 (x1/y1 exclusive).
$pixelShapes = @(
    @(5, 2, 7, 4),    # j dot
    @(5, 5, 7, 13),   # j stem
    @(3, 12, 6, 14),  # j foot
    @(9, 5, 11, 13),  # r stem
    @(11, 5, 14, 7)   # r arm
)
foreach ($n in 16, 20, 24, 32) {
    $u = $n / 16.0
    $r = [Math]::Max(2, [Math]::Round(3 * $u))
    $draws = @()
    foreach ($s in $pixelShapes) {
        $x0 = [Math]::Round($s[0] * $u); $y0 = [Math]::Round($s[1] * $u)
        # width from the shape's own size, not from two rounded edges, so equal strokes stay equal
        $x1 = $x0 + [Math]::Round(($s[2] - $s[0]) * $u) - 1; $y1 = $y0 + [Math]::Round(($s[3] - $s[1]) * $u) - 1
        $draws += '-draw'; $draws += "rectangle $x0,$y0 $x1,$y1"
    }
    $png = Join-Path $iconDir "jr-icon-$n.tmp.png"
    & $magick -size "${n}x${n}" "gradient:#4A2E5C-#2A1836" `
        `( -size "${n}x${n}" xc:none -fill white -draw "roundrectangle 0,0 $($n-1),$($n-1) $r,$r" `) `
        -compose CopyOpacity -composite -compose Over `
        +antialias -fill white @draws -depth 8 -define png:compression-level=9 $png
    if ($LASTEXITCODE -ne 0) { throw "drawing ${n}x${n} failed" }
    $pngPaths[$n] = $png
}
$sizes = @(16, 20, 24, 32) + $sizes

# ICONDIR (6 bytes) + one ICONDIRENTRY (16 bytes) per image, PNG bytes concatenated after -
# every entry PNG-compressed, per the file header above.
# @(...) is load-bearing, not decoration: with exactly one size (the now-default @(256)),
# `foreach` producing one object collapses to a bare scalar rather than a 1-element array under
# Windows PowerShell 5.1, so a later $entries.Count silently reads as $null -> 0 - wrote a
# corrupt "0 images" ICONDIR header that jr's own ReIcon.java correctly refused as invalid
# (caught 2026-09-29 exactly this way). @() forces array context regardless of element count.
$entries = @(foreach ($s in $sizes) {
    $bytes = [System.IO.File]::ReadAllBytes($pngPaths[$s])
    [PSCustomObject]@{ Size = $s; Bytes = $bytes }
})

$headerSize = 6 + 16 * $entries.Count
$offset = $headerSize
$dir = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($dir)
$w.Write([uint16]0)                 # reserved
$w.Write([uint16]1)                 # type = icon
$w.Write([uint16]$entries.Count)
foreach ($e in $entries) {
    $wh = if ($e.Size -ge 256) { 0 } else { $e.Size } # 0 means 256 in the ICO format
    $w.Write([byte]$wh)              # width
    $w.Write([byte]$wh)              # height
    $w.Write([byte]0)                # color count (0 = not a palette image)
    $w.Write([byte]0)                # reserved
    $w.Write([uint16]1)              # color planes
    $w.Write([uint16]32)             # bits per pixel
    $w.Write([uint32]$e.Bytes.Length)
    $w.Write([uint32]$offset)
    $offset += $e.Bytes.Length
}
foreach ($e in $entries) { $w.Write($e.Bytes) }
$w.Flush()
$outPath = Join-Path $iconDir 'jr-icon.ico'
[System.IO.File]::WriteAllBytes($outPath, $dir.ToArray())

foreach ($p in $pngPaths.Values) { Remove-Item $p -Force }

$final = Get-Item $outPath
Write-Host "Wrote jr-icon.ico: $($final.Length) bytes, $($entries.Count) PNG-compressed sizes"
