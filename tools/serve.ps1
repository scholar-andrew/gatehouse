# Serves dist/ (the October Journey client pack) to your devices over Tailscale only: http://100.91.134.102:8467/
# Started at logon by the scheduled task "Minecraft pack server". Waits for Tailscale, and restarts the server if it stops.
$ip = '100.91.134.102'
$port = 8467
$dir = Join-Path (Split-Path $PSScriptRoot -Parent) 'dist'
$jw = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\jwebserver.exe'
while ($true) {
    $up = Get-NetIPAddress -IPAddress $ip -ErrorAction SilentlyContinue
    $busy = Get-NetTCPConnection -LocalAddress $ip -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($up -and -not $busy) {
        $p = Start-Process -FilePath $jw -ArgumentList '-b', $ip, '-p', $port, '-d', $dir, '-o', 'none' -WindowStyle Hidden -PassThru
        $p.WaitForExit()
    }
    Start-Sleep -Seconds 15
}
