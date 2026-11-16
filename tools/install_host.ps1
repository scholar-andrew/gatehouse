# Installs the Gatehouse host helper on the PC that runs the server (safe to run again: it updates in place):
#   - copies host/oj-host.jar to C:\Minecraft\GatehouseHost and writes host.json there if it's missing
#   - registers the scheduled task "Gatehouse host helper": runs the helper (javaw, no window) when you sign in,
#     in your own session (so MCSS opens on your desktop when a launcher starts it), restarted if it stops
#   - starts it now
#   - adds "Gatehouse host panel" to your Start menu (through a one-off task, so it lands in your real Start menu)
#   powershell -ExecutionPolicy Bypass -File tools\install_host.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = 'C:\Minecraft\GatehouseHost'
$oldDir = 'C:\Minecraft\OctoberJourneyHost'   # (its folder before the tool was called Gatehouse)
$javaw = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\javaw.exe'
# Stop a running helper first (Windows locks its jar while it runs), under its old name too, and the host panel if it's
# open. schtasks, since the ScheduledTasks cmdlets can fail here.
# (Through cmd, so a task that doesn't exist, the new name on the first run or the old one later, is just skipped.)
foreach ($tn in 'Gatehouse host helper', 'October Journey host helper') { cmd /c "schtasks /end /tn `"$tn`" >nul 2>&1" }
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*oj-host.jar*' -and ($_.CommandLine -like '*GatehouseHost*' -or $_.CommandLine -like '*OctoberJourneyHost*') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep 2
# From before the rename: the old folder (settings, launchers, players, log) becomes the new one, and the old task goes.
if ((Test-Path (Join-Path $oldDir 'host.json')) -and -not (Test-Path (Join-Path $dir 'host.json'))) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    Move-Item $oldDir $dir
    "moved $oldDir to $dir"
}
cmd /c 'schtasks /delete /tn "October Journey host helper" /f >nul 2>&1'
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
$have = @($now.servers | ForEach-Object { $_ })
foreach ($ps in @($hostProfile.servers | ForEach-Object { $_ })) {
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
Register-ScheduledTask -TaskName 'Gatehouse host helper' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'Gatehouse: lets allowed launchers look after the Minecraft servers on this PC (Tailscale only). Panel: Start menu, Gatehouse host panel.' -Force | Out-Null
schtasks /run /tn "Gatehouse host helper" | Out-Null
# The Start menu shortcut, made by a one-off task in your own session.
$lnk = @"
Remove-Item ([Environment]::GetFolderPath('Programs') + '\October Journey host panel.lnk') -ErrorAction SilentlyContinue
`$s = (New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Programs') + '\Gatehouse host panel.lnk')
`$s.TargetPath = '$javaw'; `$s.Arguments = '-jar "$dir\oj-host.jar" --panel'; `$s.WorkingDirectory = '$dir'; `$s.Description = 'Gatehouse: who may do what on this server machine, and its status'; `$s.Save()
"@
$enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($lnk))
$one = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -EncodedCommand $enc"
Register-ScheduledTask -TaskName 'Gatehouse host panel shortcut (one-off)' -Action $one -Principal $principal -Force | Out-Null
schtasks /run /tn "Gatehouse host panel shortcut (one-off)" | Out-Null
Start-Sleep 5
schtasks /delete /tn "Gatehouse host panel shortcut (one-off)" /f | Out-Null
"installed in $dir"
