# What the Gatehouse Steam shortcuts run on Windows (setup points them here: <instance>\minecraft\extras\, kept up to
# date with the pack). It opens the Gatehouse launcher window, which lists the packs installed in Prism Launcher with
# whether each server is online and a Play button; with -Play <instance id> it starts that pack straight away.
#   -Prism      prismlauncher.exe (setup passes it; otherwise the usual place)
#   -Instances  Prism's instances folder (otherwise worked out from where this file is)
# It only finds Java and the launcher and runs it; everything else is in the launcher (extras\launch-popup.jar).
param([string]$Prism, [string]$Instances, [string]$Play)
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Instances) { $Instances = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $here)) }
if (-not $Prism -or -not (Test-Path $Prism)) { $Prism = Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe' }
function Prism-Instead {
    # Without Java or the launcher (a pack that has never been started yet): Prism Launcher itself does the job.
    if ($env:OJ_HOME_DRY) { "would start Prism Launcher itself: $Prism $Play"; exit 0 }   # (testing)
    if ($Play) { Start-Process -Wait $Prism -ArgumentList '--launch', $Play } else { Start-Process -Wait $Prism }
    exit 0
}
# The newest launcher among the installed packs.
$jar = Get-ChildItem $Instances -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'minecraft\extras\launch-popup.jar' } |
    Where-Object { Test-Path $_ } | Sort-Object { (Get-Item $_).LastWriteTime } -Descending | Select-Object -First 1
if (-not $jar) { Prism-Instead }
# Java 17 or newer: the ones Prism downloaded for itself (in its data folder), then ones installed on the computer.
$javaHomes = @()
foreach ($root in @((Split-Path -Parent $Instances), (Join-Path $env:APPDATA 'PrismLauncher')) | Select-Object -Unique) {
    $j = Join-Path $root 'java'
    if (Test-Path $j) { $javaHomes += @(Get-ChildItem $j -Recurse -Filter javaw.exe -ErrorAction SilentlyContinue | ForEach-Object { Split-Path -Parent (Split-Path -Parent $_.FullName) }) }
}
foreach ($pf in @($env:ProgramW6432, $env:ProgramFiles) | Where-Object { $_ } | Select-Object -Unique) {
    foreach ($vendor in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu') { $v = Join-Path $pf $vendor; if (Test-Path $v) { $javaHomes += @(Get-ChildItem $v -Directory | ForEach-Object FullName) } }
}
$java = $null; $best = 0
foreach ($h in $javaHomes) {
    $exe = Join-Path $h 'bin\javaw.exe'; $rel = Join-Path $h 'release'
    if ((Test-Path $exe) -and (Test-Path $rel) -and ((Get-Content $rel -Raw) -match 'JAVA_VERSION="(\d+)')) { $v = [int]$Matches[1]; if ($v -ge 17 -and $v -gt $best) { $java = $exe; $best = $v } }
}
if (-not $java) { Prism-Instead }
# Run from a copy: Windows locks a running file, and the pack's update has to be able to replace the launcher.
$run = Join-Path (Split-Path -Parent $Instances) 'gatehouse-launcher-run.jar'
try { Copy-Item $jar $run -Force } catch { $run = $jar }
$launcherArgs = @('-jar', "`"$run`"", '--home', "`"$Instances`"", '--prism', "`"$Prism`"")
if ($Play) { $launcherArgs += @('--play', $Play) }
$p = Start-Process -Wait -PassThru $java -ArgumentList $launcherArgs
# 12: it fetched a newer copy of itself. Open that one (once: it's told not to look again).
if ($p.ExitCode -eq 12) {
    $jar = Get-ChildItem $Instances -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'minecraft\extras\launch-popup.jar' } |
        Where-Object { Test-Path $_ } | Sort-Object { (Get-Item $_).LastWriteTime } -Descending | Select-Object -First 1
    try { Copy-Item $jar $run -Force } catch { $run = $jar }
    $launcherArgs[1] = "`"$run`""
    $env:GATEHOUSE_UPDATED = '1'
    Start-Process -Wait $java -ArgumentList $launcherArgs
}
