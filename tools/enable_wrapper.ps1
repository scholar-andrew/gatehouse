# Switches the launch wrapper on for an existing October Journey instance on Windows (new installs get it from setup):
# adds Prism's wrapper command to the instance's settings, so Quit in the launcher cancels a launch without Prism
# treating it as a failure. Prism must be closed (it rewrites the settings file when it runs), and the instance must
# already have launch-wrapper.exe (it arrives with pack 1.9.3 or later, on the first launch).
# The old settings are kept as instance.cfg.before-wrapper. -Report <file> also writes what happened there (for
# running it as a scheduled task, outside an app that redirects AppData).
param([string]$Instance = (Join-Path $env:APPDATA 'PrismLauncher\instances\october-journey'), [string]$Report = '')
$out = @()
try {
    $cfg = Join-Path $Instance 'instance.cfg'
    if (Get-Process prismlauncher -ErrorAction SilentlyContinue) { throw 'Prism Launcher is running: close it first' }
    if (-not (Test-Path $cfg)) { throw "no instance at $Instance" }
    # (The update leaves it in extras; the sync script copies it beside itself from the launch after. Do that now if needed.)
    $w = Join-Path $Instance 'minecraft\launch-wrapper.exe'; $we = Join-Path $Instance 'minecraft\extras\launch-wrapper.exe'
    if (-not (Test-Path $w) -and (Test-Path $we)) { Copy-Item -LiteralPath $we $w }
    if (-not (Test-Path (Join-Path $Instance 'minecraft\launch-wrapper.exe'))) { throw 'launch-wrapper.exe is not there yet: launch the game once on pack 1.9.3 or later' }
    $lines = @(Get-Content -LiteralPath $cfg)
    if (-not (Test-Path "$cfg.before-wrapper")) { Copy-Item -LiteralPath $cfg "$cfg.before-wrapper" }
    $lines = @($lines | Where-Object { $_ -notmatch '^WrapperCommand=' })
    $at = [Array]::FindIndex([string[]]$lines, [Predicate[string]] { param($l) $l -match '^PreLaunchCommand=' })
    if ($at -lt 0) { throw 'the instance has no pre-launch command (not set up by October Journey setup?)' }
    # A forward slash before the file name: Prism reads a backslash inside a wrapper command's quotes as an escape and
    # drops it (unlike in the pre-launch command), which made the path wrong and every launch fail at once.
    # The full path, not $INST_MC_DIR: the wrapper ran fine with Prism's own start command outside Prism, yet Prism
    # failed the launch at once with the placeholder in the setting, so it isn't relied on here.
    $new = 'WrapperCommand="\"' + ((Join-Path $Instance 'minecraft') -replace '\\', '/') + '/launch-wrapper.exe\""'
    $lines = @($lines[0..$at]) + $new + @(if ($at + 1 -lt $lines.Count) { $lines[($at + 1)..($lines.Count - 1)] })
    if (-not ($lines -match '^OverrideCommands=true')) { $lines = @($lines[0]) + 'OverrideCommands=true' + @($lines[1..($lines.Count - 1)]) }
    [IO.File]::WriteAllLines($cfg, [string[]]$lines)
    $out += "wrapper switched on in $cfg"
    $out += @(Get-Content -LiteralPath $cfg | Where-Object { $_ -match 'Command' })
} catch { $out += "NOT DONE: $($_.Exception.Message)" }
$out
if ($Report) { $out | Set-Content -LiteralPath $Report -Encoding utf8 }
