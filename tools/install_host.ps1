# Installs the October Journey host helper on the PC that runs the server (safe to run again: it updates in place):
#   - copies host/oj-host.jar to C:\Minecraft\OctoberJourneyHost and writes host.json there if it's missing
#   - registers the scheduled task "October Journey host helper": runs the helper (javaw, no window) when you sign in,
#     in your own session (so MCSS opens on your desktop when a launcher starts it), restarted if it stops
#   - starts it now
#   - adds "October Journey host panel" to your Start menu (through a one-off task, so it lands in your real Start menu)
#   powershell -ExecutionPolicy Bypass -File tools\install_host.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dir = 'C:\Minecraft\OctoberJourneyHost'
$javaw = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\javaw.exe'
New-Item -ItemType Directory -Force $dir | Out-Null
# Stop a running helper first (Windows locks its jar while it runs). schtasks, since the ScheduledTasks cmdlets can fail here.
schtasks /end /tn "October Journey host helper" 2>$null | Out-Null
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $_.CommandLine -like '*OctoberJourneyHost*oj-host.jar*--serve*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep 2
Copy-Item (Join-Path $root 'host\oj-host.jar') (Join-Path $dir 'oj-host.jar') -Force
$cfg = Join-Path $dir 'host.json'
if (-not (Test-Path $cfg)) {
    [IO.File]::WriteAllText($cfg, (@{ bind = '100.91.134.102'; port = '25570'; mcss_exe = 'C:\Minecraft\MCSS\mcss.exe'; minecraft = '127.0.0.1:25565'; tailscale_exe = 'C:\Program Files\Tailscale\tailscale.exe' } | ConvertTo-Json))
}
$action = New-ScheduledTaskAction -Execute $javaw -Argument "-jar `"$dir\oj-host.jar`" --serve" -WorkingDirectory $dir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'October Journey host helper' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'Lets allowed October Journey launchers start and close MCSS (Tailscale only). Panel: Start menu, October Journey host panel.' -Force | Out-Null
schtasks /run /tn "October Journey host helper" | Out-Null
# The Start menu shortcut, made by a one-off task in your own session.
$lnk = @"
`$s = (New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Programs') + '\October Journey host panel.lnk')
`$s.TargetPath = '$javaw'; `$s.Arguments = '-jar "$dir\oj-host.jar" --panel'; `$s.WorkingDirectory = '$dir'; `$s.Description = 'Allow launchers to start and close MCSS'; `$s.Save()
"@
$enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($lnk))
$one = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -EncodedCommand $enc"
Register-ScheduledTask -TaskName 'October Journey host panel shortcut (one-off)' -Action $one -Principal $principal -Force | Out-Null
schtasks /run /tn "October Journey host panel shortcut (one-off)" | Out-Null
Start-Sleep 5
schtasks /delete /tn "October Journey host panel shortcut (one-off)" /f | Out-Null
"installed in $dir"
