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
# The launcher ends with 12 when a newer copy of itself is in place (it updated itself): the newest is then opened in
# its place, each time that happens (GATEHOUSE_RELAUNCH tells the launcher that this script does that). Only the
# launcher itself is waited for, not programs it started: Prism Launcher may still be open, and waiting for that
# too would leave an updated launcher unopened until Prism closed.
$env:GATEHOUSE_RELAUNCH = 'loop'
$runs = @((Join-Path (Split-Path -Parent $Instances) 'gatehouse-launcher-run.jar'), (Join-Path (Split-Path -Parent $Instances) 'gatehouse-launcher-run-2.jar'))
for ($round = 0; $round -lt 8; $round++) {
    if ($round -gt 0) {
        $jar = Get-ChildItem $Instances -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'minecraft\extras\launch-popup.jar' } |
            Where-Object { Test-Path $_ } | Sort-Object { (Get-Item $_).LastWriteTime } -Descending | Select-Object -First 1
        if (-not $jar) { break }
    }
    # (the copy that just ran can stay locked for a moment after it ends: a second name, and a few tries)
    $run = $null
    foreach ($try in 1..10) {
        foreach ($r in $runs) { try { Copy-Item $jar $r -Force -ErrorAction Stop; $run = $r; break } catch { } }
        if ($run) { break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $run) { $run = $jar }
    $launcherArgs = @('-jar', "`"$run`"", '--home', "`"$Instances`"", '--prism', "`"$Prism`"")
    if ($Play -and $round -eq 0) { $launcherArgs += @('--play', $Play) }
    $p = Start-Process -PassThru $java -ArgumentList $launcherArgs
    $null = $p.Handle   # (so its exit code is kept)
    $p.WaitForExit()
    if ($p.ExitCode -ne 12) { break }
    $env:GATEHOUSE_UPDATED = '1'
}
