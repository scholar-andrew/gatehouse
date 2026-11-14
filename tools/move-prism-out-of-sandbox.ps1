# One-off fix (2026-11-14): Prism's data on this PC was written into the Claude app's sandboxed AppData
# (AppData\Local\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\PrismLauncher) because Prism and Steam were started from
# inside the Claude app. Run outside the app (as a scheduled task), this copies it to the real %APPDATA%\PrismLauncher,
# never overwriting anything already there, and leaves the sandbox copy in place. Report: .cache\prism-move-report.txt
$ErrorActionPreference = 'Stop'
$report = Join-Path (Split-Path $PSScriptRoot -Parent) '.cache\prism-move-report.txt'
$lines = @("run $(Get-Date -Format s) as $env:USERNAME")
try {
    $busy = Get-Process steam, prismlauncher, javaw -ErrorAction SilentlyContinue
    if ($busy) { throw "still running: $(($busy | ForEach-Object Name | Sort-Object -Unique) -join ', ')" }
    $src = Join-Path $env:LOCALAPPDATA 'Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\PrismLauncher'
    $dst = Join-Path $env:APPDATA 'PrismLauncher'
    $lines += "source exists: $(Test-Path $src)"
    $lines += "real folder existed before: $(Test-Path $dst)"
    if (Test-Path $dst) { $lines += 'real folder entries before: ' + ((Get-ChildItem $dst -Force | ForEach-Object Name) -join ', ') }
    # /E all folders, /XC /XN /XO never replace an existing file, /R:1 /W:1 short retries.
    robocopy $src $dst /E /XC /XN /XO /R:1 /W:1 /NFL /NDL /NJH /NP | Out-Null
    $lines += "robocopy exit $LASTEXITCODE (0-7 is success)"
    $lines += "instance now in real folder: $(Test-Path (Join-Path $dst 'instances\october-journey\instance.cfg'))"
    $lines += "accounts now in real folder: $(Test-Path (Join-Path $dst 'accounts.json'))"
    $lines += "mods in real instance: $((Get-ChildItem (Join-Path $dst 'instances\october-journey\minecraft\mods') -Filter *.jar -ErrorAction SilentlyContinue).Count)"
    $lines += "Prism program present: $(Test-Path (Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe'))"
} catch { $lines += "ERROR: $($_.Exception.Message)" }
$lines | Set-Content -Encoding utf8 $report
