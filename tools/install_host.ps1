# Installs Gatekeeper (the host helper) on the PC that runs the server (safe to run again: it updates in place):
#   - copies host/oj-host.jar to C:\Minecraft\GatehouseKeeper and writes host.json there if it's missing
#   - registers the scheduled task "Gatehouse Keeper": runs it (javaw, no window) when you sign in,
#     in your own session (so MCSS opens on your desktop when a launcher starts it), restarted if it stops
#   - starts it now
#   - adds "Gatekeeper" to your Start menu (through a one-off task, so it lands in your real Start menu)
#   powershell -ExecutionPolicy Bypass -File tools\install_host.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = 'C:\Minecraft\GatehouseKeeper'
# Its folders and tasks under earlier names (Gatehouse host helper; before that, October Journey host helper).
$oldDirs = 'C:\Minecraft\GatehouseHost', 'C:\Minecraft\OctoberJourneyHost'
$oldTasks = 'Gatehouse host helper', 'October Journey host helper'
$javaw = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\javaw.exe'
# Stop a running Gatekeeper first (Windows locks its jar while it runs), under its old names too, and Gatekeeper if
# it's open. Through cmd, so a task that doesn't exist (the new name on the first run, an old one later) is just skipped.
foreach ($tn in @('Gatehouse Keeper') + $oldTasks) { cmd /c "schtasks /end /tn `"$tn`" >nul 2>&1" }
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*oj-host.jar*' -and ($_.CommandLine -like '*GatehouseKeeper*' -or $_.CommandLine -like '*GatehouseHost*' -or $_.CommandLine -like '*OctoberJourneyHost*') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep 2
# From before a rename: the old folder (settings, launchers, players, log) becomes the new one, and the old tasks go.
foreach ($oldDir in $oldDirs) {
    if ((Test-Path (Join-Path $oldDir 'host.json')) -and -not (Test-Path (Join-Path $dir 'host.json'))) {
        if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
        Move-Item $oldDir $dir
        "moved $oldDir to $dir"
    }
}
foreach ($tn in $oldTasks) { cmd /c "schtasks /delete /tn `"$tn`" /f >nul 2>&1" }
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item (Join-Path $root 'host\oj-host.jar') (Join-Path $dir 'oj-host.jar') -Force
# host.json: what the server profile says about this machine (installer\config.json "host": its address, MCSS, and the
# servers it looks after), put in wherever host.json doesn't say yet. What's already there (and the helper's own MCSS
# sign-in) is kept.
$cfg = Join-Path $dir 'host.json'
$hostProfile = (Get-Content (Join-Path $root 'installer\config.json') -Raw | ConvertFrom-Json).host
$now = if (Test-Path $cfg) { Get-Content $cfg -Raw | ConvertFrom-Json } else { [pscustomobject]@{} }
foreach ($k in 'bind', 'port', 'mcss_exe', 'tailscale_exe', 'servers') {
    if ($null -eq $now.$k -or "$($now.$k)" -eq '') {
        $v = $hostProfile.$k
        if ($k -eq 'port') { $v = "$v" }
        if ($k -eq 'servers') { $v = @($v | ForEach-Object { $_ }) }
        $now | Add-Member -NotePropertyName $k -NotePropertyValue $v -Force
    }
}
# The servers, one by one: a server host.json already has (same id, or same name from before ids) gets whatever the
# profile says that it doesn't say yet; one it doesn't have is added.
# (The servers come from every profile in this repository: October Journey's, and each profiles\<id>\config.json that
# isn't marked as an example.)
$allServers = @($hostProfile.servers | ForEach-Object { $_ })
Get-ChildItem (Join-Path $root 'profiles') -Directory -ErrorAction SilentlyContinue | ForEach-Object {
    $pc = Join-Path $_.FullName 'config.json'
    if (Test-Path $pc) { $pj = Get-Content $pc -Raw | ConvertFrom-Json; if (-not $pj.example -and $pj.host.servers) { $allServers += @($pj.host.servers | ForEach-Object { $_ }) } }
}
$have = @($now.servers | ForEach-Object { $_ })
foreach ($ps in $allServers) {
    $hit = $have | Where-Object { ($_.id -and $_.id -eq $ps.id) -or (-not $_.id -and $_.name -eq $ps.name) } | Select-Object -First 1
    if ($hit) { foreach ($pp in $ps.PSObject.Properties) { if ($null -eq $hit.($pp.Name) -or ("$($hit.($pp.Name))" -eq '' -and $pp.Name -eq 'id')) { $hit | Add-Member -NotePropertyName $pp.Name -NotePropertyValue $pp.Value -Force } } }
    else { $have += $ps }
}
$now | Add-Member -NotePropertyName servers -NotePropertyValue @($have) -Force
[IO.File]::WriteAllText($cfg, ($now | ConvertTo-Json -Depth 6))
$action = New-ScheduledTaskAction -Execute $javaw -Argument "-jar `"$dir\oj-host.jar`" --serve" -WorkingDirectory $dir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'Gatehouse Keeper' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'Gatehouse: lets allowed launchers look after the Minecraft servers on this PC (Tailscale only). Start menu: Gatekeeper.' -Force | Out-Null
schtasks /run /tn "Gatehouse Keeper" | Out-Null
# The Start menu shortcut, made by a one-off task in your own session.
$lnk = @"
foreach (`$old in 'October Journey host panel.lnk', 'Gatehouse host panel.lnk', 'Gatehouse control panel.lnk') { Remove-Item ([Environment]::GetFolderPath('Programs') + '\' + `$old) -ErrorAction SilentlyContinue }
`$s = (New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Programs') + '\Gatekeeper.lnk')
`$s.TargetPath = '$javaw'; `$s.Arguments = '-jar "$dir\oj-host.jar" --panel'; `$s.WorkingDirectory = '$dir'; `$s.Description = 'Gatehouse: who may do what on this server machine, and its status'; `$s.Save()
"@
$enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($lnk))
$one = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -EncodedCommand $enc"
Register-ScheduledTask -TaskName 'Gatekeeper shortcut (one-off)' -Action $one -Principal $principal -Force | Out-Null
schtasks /run /tn "Gatekeeper shortcut (one-off)" | Out-Null
Start-Sleep 5
schtasks /delete /tn "Gatekeeper shortcut (one-off)" /f | Out-Null
"installed in $dir"
