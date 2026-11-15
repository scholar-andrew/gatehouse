# Draws the Steam library art for the Prism Launcher shortcut: Prism Launcher's own logo (installer/art-prism/source/
# prism-logo.png, rendered from the scalable logo that ships with Prism) on a plain dark background, with its name.
# See installer/art-prism/NOTICE.md for the logo's licence. Writes installer/art-prism/:
#   cover.png 600x900, hero.png 1920x620, logo.png (transparent), icon.png 512x512, wide.png 920x430
Add-Type -AssemblyName System.Drawing
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'installer\art-prism'
$src = [System.Drawing.Image]::FromFile((Join-Path $dir 'source\prism-logo.png'))

function C($hex, [int]$a = 255) { [System.Drawing.Color]::FromArgb($a, [System.Drawing.ColorTranslator]::FromHtml($hex)) }

function Canvas([int]$w, [int]$h, [bool]$background = $true) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAliasGridFit'
    if ($background) {
        $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (C '#2b3245'), (C '#151925'), 60.0
        $g.FillRectangle($brush, $rect); $brush.Dispose()
    } else { $g.Clear([System.Drawing.Color]::Transparent) }
    return @($bmp, $g)
}

function Logo($g, [int]$size, [int]$cx, [int]$cy, [double]$alpha = 1.0) {
    $r = New-Object System.Drawing.Rectangle ([int]($cx - $size / 2)), ([int]($cy - $size / 2)), $size, $size
    if ($alpha -ge 1.0) { $g.DrawImage($src, $r); return }
    $m = New-Object System.Drawing.Imaging.ColorMatrix; $m.Matrix33 = [single]$alpha
    $ia = New-Object System.Drawing.Imaging.ImageAttributes; $ia.SetColorMatrix($m)
    $g.DrawImage($src, $r, 0, 0, $src.Width, $src.Height, [System.Drawing.GraphicsUnit]::Pixel, $ia)
}

function Name($g, [float]$px, [float]$x, [float]$y, [string]$align = 'Center') {
    $font = New-Object System.Drawing.Font 'Segoe UI Semibold', $px, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Object System.Drawing.StringFormat; $fmt.Alignment = $align; $fmt.LineAlignment = 'Center'
    $g.DrawString('Prism Launcher', $font, (New-Object System.Drawing.SolidBrush (C '#000000' 110)), $x + 3, $y + 3, $fmt)
    $g.DrawString('Prism Launcher', $font, (New-Object System.Drawing.SolidBrush (C '#f2f4f8')), $x, $y, $fmt)
}

function Save($pair, [string]$name) { $pair[1].Dispose(); $pair[0].Save((Join-Path $dir $name), [System.Drawing.Imaging.ImageFormat]::Png); $pair[0].Dispose() }

$c = Canvas 600 900; Logo $c[1] 360 300 360; Name $c[1] 62 300 640; Save $c 'cover.png'
$c = Canvas 1920 620; Logo $c[1] 820 1500 310 0.22; Save $c 'hero.png'
$c = Canvas 1200 300 $false; Logo $c[1] 260 150 150; Name $c[1] 104 310 150 'Near'; Save $c 'logo.png'
$c = Canvas 512 512 $false; Logo $c[1] 512 256 256; Save $c 'icon.png'
$c = Canvas 920 430; Logo $c[1] 230 230 215; Name $c[1] 64 390 215 'Near'; Save $c 'wide.png'
$src.Dispose()
Get-ChildItem $dir -File | ForEach-Object { "{0} {1}" -f $_.Name, $_.Length }
