# Draws the Steam library art for the "Gatehouse Minecraft Launcher" shortcut: an original gatehouse mark (two towers
# and a gate, drawn here from plain shapes) on a dark blue background, with the name. Writes installer/art-launcher/:
#   cover.png 600x900, hero.png 1920x620, logo.png (transparent), icon.png 512x512, wide.png 920x430
#   powershell -ExecutionPolicy Bypass -File tools\make_launcher_art.ps1
Add-Type -AssemblyName System.Drawing
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'installer\art-launcher'
New-Item -ItemType Directory -Force $dir | Out-Null

function C($hex, [int]$a = 255) { [System.Drawing.Color]::FromArgb($a, [System.Drawing.ColorTranslator]::FromHtml($hex)) }

function Canvas([int]$w, [int]$h, [bool]$background = $true) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'
    if ($background) {
        $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (C '#2a475e'), (C '#141d2b'), 65.0
        $g.FillRectangle($brush, $rect); $brush.Dispose()
    } else { $g.Clear([System.Drawing.Color]::Transparent) }
    return @($bmp, $g)
}

# The mark, in a box of the given size centred on (cx, cy): two towers with battlements, a wall between them with its
# own battlements, and an arched gate with a raised portcullis. All in units of size/20.
function Mark($g, [double]$size, [double]$cx, [double]$cy, [double]$alpha = 1.0) {
    $u = $size / 20.0; $x0 = $cx - $size / 2; $y0 = $cy - $size / 2
    $a = [int](255 * $alpha)
    $stone = New-Object System.Drawing.SolidBrush (C '#dfe9f3' $a)
    $shade = New-Object System.Drawing.SolidBrush (C '#66c0f4' $a)
    $dark = New-Object System.Drawing.SolidBrush (C '#16202d' $a)
    function Box($b, $x, $y, $w, $h) { $g.FillRectangle($b, [single]($x0 + $x * $u), [single]($y0 + $y * $u), [single]($w * $u), [single]($h * $u)) }
    # towers (x 1..6 and 14..19), with three merlons each
    foreach ($tx in 1, 14) {
        Box $stone $tx 5 5 14
        foreach ($m in 0, 2, 4) { Box $stone ($tx + $m) 3 1 2 }
        Box $dark ($tx + 2) 8 1 2.4      # arrow slit
        Box $shade $tx 18 5 1            # footing
    }
    # the wall between, lower, with merlons
    Box $stone 6 8 8 11
    foreach ($m in 6.5, 8.5, 10.5, 12.5) { Box $stone $m 6.6 1 1.4 }
    Box $shade 6 18 8 1
    # the gate: an arch cut out of the wall
    $gate = New-Object System.Drawing.Drawing2D.GraphicsPath
    $gx = $x0 + 7.6 * $u; $gy = $y0 + 10.6 * $u; $gw = 4.8 * $u
    $gate.AddArc([single]$gx, [single]$gy, [single]$gw, [single]$gw, 180, 180)
    $gate.AddLine([single]($gx + $gw), [single]($gy + $gw / 2), [single]($gx + $gw), [single]($y0 + 19 * $u))
    $gate.AddLine([single]($gx + $gw), [single]($y0 + 19 * $u), [single]$gx, [single]($y0 + 19 * $u))
    $gate.CloseFigure()
    $g.FillPath($dark, $gate)
    # the portcullis, half raised: bars with points
    $old = $g.Clip; $g.SetClip($gate)
    foreach ($bx in 8.3, 9.4, 10.5, 11.6) { Box $shade $bx 10 0.36 4.6 }
    Box $shade 7.6 12.6 4.8 0.36
    $g.Clip = $old
    $gate.Dispose(); $stone.Dispose(); $shade.Dispose(); $dark.Dispose()
}

function Words($g, [float]$px, [float]$x, [float]$y, [string]$align = 'Center', [bool]$second = $true) {
    $fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = $align; $fmt.LineAlignment = 'Center'
    $big = New-Object System.Drawing.Font 'Segoe UI Semibold', $px, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString('Gatehouse', $big, (New-Object System.Drawing.SolidBrush (C '#000000' 110)), $x + 3, $y + 3, $fmt)
    $g.DrawString('Gatehouse', $big, (New-Object System.Drawing.SolidBrush (C '#f2f6fa')), $x, $y, $fmt)
    if ($second) {
        $small = New-Object System.Drawing.Font 'Segoe UI', ($px * 0.42), ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
        $g.DrawString('Minecraft Launcher', $small, (New-Object System.Drawing.SolidBrush (C '#66c0f4')), $x, $y + $px * 0.82, $fmt)
    }
}

function Save($pair, [string]$name) { $pair[1].Dispose(); $pair[0].Save((Join-Path $dir $name), [System.Drawing.Imaging.ImageFormat]::Png); $pair[0].Dispose() }

$c = Canvas 600 900; Mark $c[1] 380 300 350; Words $c[1] 84 300 640; Save $c 'cover.png'
$c = Canvas 1920 620; Mark $c[1] 760 1480 330 0.20; Save $c 'hero.png'
$c = Canvas 1200 300 $false; Mark $c[1] 250 150 150; Words $c[1] 112 320 120 'Near'; Save $c 'logo.png'
$c = Canvas 512 512; Mark $c[1] 400 256 256; Save $c 'icon.png'
$c = Canvas 920 430; Mark $c[1] 250 220 215; Words $c[1] 78 400 185 'Near'; Save $c 'wide.png'
Get-ChildItem $dir -File | ForEach-Object { "{0} {1}" -f $_.Name, $_.Length }
