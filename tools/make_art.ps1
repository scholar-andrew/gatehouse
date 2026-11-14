# Draws the October Journey Steam library art (original artwork, no third-party images): an autumn evening, blocky hills
# and a great tree, with the title in blocky letters. Same output every run (fixed seed). Writes installer/art/:
#   cover.png 600x900, hero.png 1920x620, logo.png (transparent), icon.png 512x512, wide.png 920x430
Add-Type -AssemblyName System.Drawing
$out = Join-Path (Split-Path $PSScriptRoot -Parent) 'installer\art'
New-Item -ItemType Directory -Force $out | Out-Null

function C($hex) { [System.Drawing.ColorTranslator]::FromHtml($hex) }

# A scene drawn on a coarse grid of "blocks" (b pixels each), then upscaled without smoothing for the blocky look.
function Scene([int]$w, [int]$h, [int]$b, [double]$treeX, [int]$seed, [double]$treeTop = 0.38) {
    $gw = [int][Math]::Ceiling($w / $b); $gh = [int][Math]::Ceiling($h / $b)
    $bmp = New-Object System.Drawing.Bitmap $gw, $gh
    $rnd = New-Object System.Random $seed
    $sky = @('#1d1b3a', '#2b2350', '#45305e', '#7a3f5c', '#c25b4a', '#e88a45')
    for ($y = 0; $y -lt $gh; $y++) {
        $t = $y / [double]$gh; $i = [Math]::Min($sky.Count - 1, [int]($t * ($sky.Count) * 1.15))
        for ($x = 0; $x -lt $gw; $x++) { $bmp.SetPixel($x, $y, (C $sky[$i])) }
    }
    # Stars in the top of the sky.
    for ($k = 0; $k -lt [int]($gw * $gh / 90); $k++) { $x = $rnd.Next($gw); $y = $rnd.Next([int]($gh * 0.35)); $bmp.SetPixel($x, $y, (C '#f3e6c4')) }
    # Rolling hills: a far darker ridge, then the near ground with grass, dirt and stone layers.
    $far = [int]($gh * 0.66); $near = [int]($gh * 0.80)
    for ($x = 0; $x -lt $gw; $x++) {
        $fy = $far + [int](3 * [Math]::Sin($x / 7.0 + $seed) + 2 * [Math]::Sin($x / 3.3))
        for ($y = $fy; $y -lt $gh; $y++) { $bmp.SetPixel($x, $y, (C '#3d2a3f')) }
        $ny = $near + [int](2 * [Math]::Sin($x / 5.0 + 1) + [Math]::Sin($x / 2.1))
        for ($y = $ny; $y -lt $gh; $y++) {
            $d = $y - $ny
            $col = if ($d -eq 0) { '#c8722d' } elseif ($d -lt 2) { '#8f5a2b' } elseif ($d -lt 9) { '#6b4423' } else { '#4f4a52' }
            $bmp.SetPixel($x, $y, (C $col))
        }
    }
    # The great tree: a thick trunk with a wide crown of autumn leaves.
    $tx = [int]($gw * $treeX); $trunkW = [Math]::Max(3, [int]($gh * 0.07)); $base = $near
    $top = [int]($gh * $treeTop)
    for ($y = $top; $y -lt $base + 1; $y++) { for ($x = $tx - [int]($trunkW / 2); $x -le $tx + [int]($trunkW / 2); $x++) { if ($x -ge 0 -and $x -lt $gw) { $bmp.SetPixel($x, $y, (C ($(if (($x + $y) % 3 -eq 0) { '#4a2f1d' } else { '#5c3a24' })))) } } }
    $leaves = @('#e0592a', '#f08c2e', '#c93b22', '#f2b33d', '#a8331f')
    $r = [Math]::Max(5, [int]($gh * 0.17))
    foreach ($blob in @(@(0, 0, 1.0), @(-0.7, 0.35, 0.65), @(0.7, 0.35, 0.65), @(0, -0.55, 0.6))) {
        $cx = $tx + [int]($blob[0] * $r); $cy = $top + [int]($blob[1] * $r); $rr = $blob[2] * $r
        for ($y = [int]($cy - $rr); $y -le $cy + $rr; $y++) { for ($x = [int]($cx - $rr * 1.3); $x -le $cx + $rr * 1.3; $x++) {
            if ($x -lt 0 -or $y -lt 0 -or $x -ge $gw -or $y -ge $gh) { continue }
            $dx = ($x - $cx) / 1.3; $dy = $y - $cy
            if ($dx * $dx + $dy * $dy -le $rr * $rr -and $rnd.NextDouble() -gt 0.06) { $bmp.SetPixel($x, $y, (C $leaves[$rnd.Next($leaves.Count)])) }
        } }
    }
    # A few falling leaves.
    for ($k = 0; $k -lt [int]($gw / 4); $k++) { $x = $rnd.Next($gw); $y = $rnd.Next([int]($gh * 0.3), $near); $bmp.SetPixel($x, $y, (C $leaves[$rnd.Next($leaves.Count)])) }
    $big = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($big)
    $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
    $g.DrawImage($bmp, 0, 0, $gw * $b, $gh * $b)
    $g.Dispose(); $bmp.Dispose()
    return $big
}

# Blocky title: drawn small without smoothing, then scaled up by whole pixels, with a dark outline and drop shadow.
function Title([string[]]$lines, [int]$scale) {
    $font = New-Object System.Drawing.Font 'Consolas', 11, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $probe = New-Object System.Drawing.Bitmap 1, 1; $pg = [System.Drawing.Graphics]::FromImage($probe)
    $sizes = $lines | ForEach-Object { $pg.MeasureString($_, $font) }
    $sw = [int](($sizes | Measure-Object Width -Maximum).Maximum) + 6; $sh = [int](12 * $lines.Count) + 6
    $pg.Dispose(); $probe.Dispose()
    $small = New-Object System.Drawing.Bitmap $sw, $sh
    $g = [System.Drawing.Graphics]::FromImage($small)
    $g.TextRenderingHint = 'SingleBitPerPixelGridFit'; $g.Clear([System.Drawing.Color]::Transparent)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $lw = $g.MeasureString($lines[$i], $font).Width
        $x = [int](($sw - $lw) / 2); $y = 3 + 12 * $i
        foreach ($o in @(@(-1, 0), @(1, 0), @(0, -1), @(0, 1), @(1, 1), @(2, 2))) { $g.DrawString($lines[$i], $font, (New-Object System.Drawing.SolidBrush (C '#24131b')), $x + $o[0], $y + $o[1]) }
        $g.DrawString($lines[$i], $font, (New-Object System.Drawing.SolidBrush (C ($(if ($i -eq 0) { '#ffd27a' } else { '#ffffff' })))), $x, $y)
    }
    $g.Dispose()
    $big = New-Object System.Drawing.Bitmap ($sw * $scale), ($sh * $scale)
    $g = [System.Drawing.Graphics]::FromImage($big)
    $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
    $g.DrawImage($small, 0, 0, $sw * $scale, $sh * $scale)
    $g.Dispose(); $small.Dispose()
    return $big
}

function Put($img, $logo, [int]$width, [int]$cy, [double]$cx = 0.5) {
    $g = [System.Drawing.Graphics]::FromImage($img); $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
    $lh = [int]($logo.Height * $width / $logo.Width)
    $g.DrawImage($logo, [int]($img.Width * $cx - $width / 2), [int]($cy - $lh / 2), $width, $lh); $g.Dispose()
}

$logo = Title @('OCTOBER', 'JOURNEY') 12
$logo.Save((Join-Path $out 'logo.png'), [System.Drawing.Imaging.ImageFormat]::Png)

$cover = Scene 600 900 12 0.5 7 0.56;  Put $cover $logo 520 175; $cover.Save((Join-Path $out 'cover.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$hero = Scene 1920 620 16 0.76 3 0.36; $hero.Save((Join-Path $out 'hero.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$wide = Scene 920 430 10 0.80 5 0.36;  Put $wide $logo 520 165 0.36; $wide.Save((Join-Path $out 'wide.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$icon = Scene 512 512 16 0.5 11 0.36;  $icon.Save((Join-Path $out 'icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
foreach ($i in @($logo, $cover, $hero, $wide, $icon)) { $i.Dispose() }
Get-ChildItem $out | ForEach-Object { "{0} {1}" -f $_.Name, $_.Length }
