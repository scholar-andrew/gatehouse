# Gatekeeper setup for Windows: installs the program that runs on the machine hosting the Minecraft servers
# (docs/server.md). A plain script, so you can read exactly what it does before running it. It needs oj-host.jar (the
# Gatekeeper) in the same folder as this script: both come in gatehouse-keeper.zip. It downloads nothing.
#
# Run it (after unpacking the zip):  right-click install-keeper.ps1 > Run with PowerShell, or
#   powershell -ExecutionPolicy Bypass -File install-keeper.ps1
#
# What it does (safe to run again: it updates in place and keeps the settings, launchers and players already there):
#   - needs Java 17 or newer on the machine (the Minecraft servers in MCSS use Java too). It looks in the usual places
#     and stops, saying where to get it, if there is none.
#   - copies Gatekeeper to its folder (an install that's already there stays where it is; a new one goes to
#     %LOCALAPPDATA%\Programs\GatehouseKeeper) and writes host.json there if it's missing
#   - starts it when you sign in to Windows, with no window: a scheduled task "Gatehouse Keeper", restarted if it stops;
#     or, where Windows doesn't let this account make that task, a shortcut in your Startup folder
#   - adds "Gatekeeper" to the Start menu
#   - starts Gatekeeper and opens its window, where Server setup (Status tab) ties it to MCSS and its servers
#
#   -Uninstall   remove it: the task, the shortcuts and the program. Settings and the launcher list are kept.
#   -Purge       with -Uninstall: remove the settings, launcher list and log too
#   -Dir DIR     where to install        -Java PATH   the javaw.exe to run it with
#   -Yes         no questions            -NoPanel     don't open Gatekeeper at the end
#   -TaskName NAME, -NoStartMenu   (testing)
param([switch]$Uninstall, [switch]$Purge, [string]$Dir, [string]$Java, [switch]$Yes, [switch]$NoPanel, [string]$TaskName = 'Gatehouse Keeper', [switch]$NoStartMenu)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$panelLink = Join-Path ([Environment]::GetFolderPath('Programs')) 'Gatekeeper.lnk'
$startupLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'Gatehouse Keeper.lnk'

function Ask($question) {
    if ($Yes) { return $true }
    return (Read-Host "$question [Y/n]") -notmatch '^\s*n'
}
function Finish($text, $code) {
    Write-Host ''
    Write-Host $text
    if (-not $Yes) { Read-Host 'Press Enter to close' | Out-Null }
    exit $code
}

# Where a Gatekeeper is installed already: where its task runs it from, or the folder the first installs used.
function ExistingDir {
    try {
        $t = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
        if ($t.Actions[0].Arguments -match '-jar "([^"]+)\\oj-host\.jar"') { if (Test-Path $Matches[1]) { return $Matches[1] } }
    } catch {}
    if (Test-Path 'C:\Minecraft\GatehouseKeeper\oj-host.jar') { return 'C:\Minecraft\GatehouseKeeper' }
    return $null
}

# javaw.exe of the newest Java that is 17 or newer: JAVA_HOME, Program Files (Temurin, Oracle, Microsoft, ...), the PATH.
function FindJava {
    $homes = @()
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    foreach ($pf in @($env:ProgramW6432, $env:ProgramFiles) | Where-Object { $_ } | Select-Object -Unique) {
        foreach ($vendor in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft') {
            $v = Join-Path $pf $vendor
            if (Test-Path $v) { $homes += @(Get-ChildItem $v -Directory | ForEach-Object FullName) }
        }
    }
    foreach ($p in ($env:PATH -split ';')) { if ($p -and (Test-Path (Join-Path $p 'javaw.exe'))) { $homes += (Split-Path -Parent $p.TrimEnd('\')) } }
    $best = $null; $bestV = 0
    foreach ($h in $homes) {
        $exe = Join-Path $h 'bin\javaw.exe'; $rel = Join-Path $h 'release'
        if (-not (Test-Path $exe) -or -not (Test-Path $rel)) { continue }
        if ((Get-Content $rel -Raw) -match 'JAVA_VERSION="(\d+)') { $v = [int]$Matches[1]; if ($v -ge 17 -and $v -gt $bestV) { $best = $exe; $bestV = $v } }
    }
    return $best
}

# Stops a Gatekeeper running from this folder (Windows locks its file while it runs) and its Gatekeeper: only
# processes that were started with this folder's oj-host.jar.
function StopKeeper($jar) {
    cmd /c "schtasks /end /tn `"$TaskName`" >nul 2>&1"
    Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -like "*$jar*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep 2
}
function Shortcut($path, $target, $arguments, $workDir, $description) {
    $s = (New-Object -ComObject WScript.Shell).CreateShortcut($path)
    $s.TargetPath = $target; $s.Arguments = $arguments; $s.WorkingDirectory = $workDir; $s.Description = $description; $s.Save()
}

if (-not $Dir) { $Dir = ExistingDir }
if (-not $Dir) { $Dir = Join-Path $env:LOCALAPPDATA 'Programs\GatehouseKeeper' }
$jar = Join-Path $Dir 'oj-host.jar'

if ($Uninstall) {
    Write-Host "Remove Gatekeeper from this machine? ($Dir)"
    Write-Host $(if ($Purge) { 'Its settings, launcher list and log are removed too.' } else { 'Its settings, launcher list and log are kept in that folder.' })
    if (-not (Ask 'Remove it?')) { Finish 'Nothing was changed.' 1 }
    StopKeeper $jar
    cmd /c "schtasks /delete /tn `"$TaskName`" /f >nul 2>&1"
    foreach ($l in $panelLink, $startupLink, (Join-Path ([Environment]::GetFolderPath('Programs')) 'Gatehouse control panel.lnk')) { if (Test-Path $l) { Remove-Item $l -Confirm:$false } }
    if (Test-Path $jar) { Remove-Item $jar -Confirm:$false }
    if ($Purge) { foreach ($f in 'host.json', 'devices.json', 'players.json', 'host.log') { $p = Join-Path $Dir $f; if (Test-Path $p) { Remove-Item $p -Confirm:$false } } }
    Finish "Gatekeeper is removed.$(if (-not $Purge) { " Its settings are still in $Dir." })" 0
}

$source = Join-Path $here 'oj-host.jar'
if (-not (Test-Path $source)) { Finish "oj-host.jar isn't beside this script. Unpack the whole gatehouse-keeper.zip and run install-keeper.ps1 from there." 2 }
if (-not $Java) { $Java = FindJava }
if (-not $Java -or -not (Test-Path $Java)) {
    Finish "Gatekeeper needs Java 17 or newer on this machine (the Minecraft servers in MCSS use Java too), and none was found.`nGet it from https://adoptium.net (Temurin, the Windows .msi), then run this again." 2
}
$update = Test-Path $jar
Write-Host "Gatekeeper: $(if ($update) { 'update' } else { 'install' }) in $Dir"
Write-Host "  Java: $Java"
Write-Host '  It runs when you sign in to Windows, with no window, and lets the launchers you allow look after the Minecraft'
Write-Host "  servers on this machine. It listens only on this machine's Tailscale address."
Write-Host '  "Gatekeeper" is added to the Start menu.'
if ($update) { Write-Host '  Your settings, launchers and players are kept.' }
if (-not (Ask 'Go ahead?')) { Finish 'Nothing was changed.' 1 }

StopKeeper $jar
New-Item -ItemType Directory -Force $Dir | Out-Null
Copy-Item $source $jar -Force
Copy-Item $MyInvocation.MyCommand.Path (Join-Path $Dir 'install-keeper.ps1') -Force -ErrorAction SilentlyContinue   # (kept there, for -Uninstall later)
$cfg = Join-Path $Dir 'host.json'
if (-not (Test-Path $cfg)) { [IO.File]::WriteAllText($cfg, "{`"port`": `"25570`"}`n") }

# At sign-in, in your own session (so MCSS opens on your desktop when a launcher starts it), no time limit, restarted
# if it stops.
$how = ''
try {
    $me = "$env:USERDOMAIN\$env:USERNAME"
    $action = New-ScheduledTaskAction -Execute $Java -Argument "-jar `"$jar`" --serve" -WorkingDirectory $Dir
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $me
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'Gatehouse: lets allowed launchers look after the Minecraft servers on this PC (Tailscale only). Start menu: Gatekeeper.' -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    if (Test-Path $startupLink) { Remove-Item $startupLink -Confirm:$false }
    $how = "It starts when you sign in to Windows (scheduled task `"$TaskName`")."
} catch {
    Shortcut $startupLink $Java "-jar `"$jar`" --serve" $Dir 'Gatehouse Keeper'
    Start-Process $Java -ArgumentList '-jar', "`"$jar`"", '--serve' -WorkingDirectory $Dir
    $how = "It starts when you sign in to Windows (a shortcut in your Startup folder: Windows didn't let this account make a scheduled task)."
}
$oldLink = Join-Path ([Environment]::GetFolderPath('Programs')) 'Gatehouse control panel.lnk'   # (its name before it was Gatekeeper)
if (-not $NoStartMenu -and (Test-Path $oldLink)) { Remove-Item $oldLink -Confirm:$false }
if (-not $NoStartMenu) { Shortcut $panelLink $Java "-jar `"$jar`" --panel" $Dir 'Gatehouse: who may do what on this server machine, and its status' }
Start-Sleep 3
if (-not $NoPanel) { Start-Process $Java -ArgumentList '-jar', "`"$jar`"", '--panel' -WorkingDirectory $Dir }
$next = if ($update) { '' } else { "`nGatekeeper is opening: in Server setup, pick MCSS and the servers Gatekeeper should look after, then sign Gatekeeper in to MCSS on the Status tab." }
Finish "Gatekeeper is $(if ($update) { 'updated' } else { 'installed' }). $how$next`nTo remove it later: powershell -ExecutionPolicy Bypass -File `"$(Join-Path $Dir 'install-keeper.ps1')`" -Uninstall" 0
