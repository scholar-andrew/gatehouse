# October Journey pack sync (Windows). Prism runs this before Minecraft starts, in the instance's minecraft folder.
# It tries each source in pack-sources.txt in order (this PC over Tailscale first, then GitHub), and brings the instance up
# to date with packwiz-installer: new and updated mods are downloaded and checked, removed ones taken out. A Windows
# Prism instance gets the main pack; Steam Deck-only mods are never installed here. Every run is noted in pack-sync.log.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $here 'pack-sync.log'
function Note($text) { Add-Content -Path $log -Value ("{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text); Write-Host "Pack sync: $text" }
$variant = 'main'
$sources = Get-Content (Join-Path $here 'pack-sources.txt') | Where-Object { $_ -and -not $_.Trim().StartsWith('#') } | ForEach-Object { $_.Trim().TrimEnd('/') }
$url = $null
foreach ($s in $sources) {
    try {
        $r = Invoke-WebRequest "$s/$variant/pack.toml" -UseBasicParsing -TimeoutSec 3 -Method Head
        if ($r.StatusCode -eq 200) { $url = "$s/$variant/pack.toml"; break }
    } catch { }
}
if (-not $url) { Note 'no source reachable; starting with the mods already installed'; exit 0 }
# Prism hands over javaw.exe, which PowerShell would not wait for: the console java.exe beside it, waited on.
$java = if ($env:INST_JAVA) { $env:INST_JAVA } else { 'java' }
$console = Join-Path (Split-Path $java -Parent) 'java.exe'
if ($java -like '*javaw.exe' -and (Test-Path $console)) { $java = $console }
$jargs = @('-jar', (Join-Path $here 'packwiz-installer-bootstrap.jar'))
if ($env:PACK_SYNC_ARGS) { $jargs += $env:PACK_SYNC_ARGS -split ' ' }
$jargs += $url
$p = Start-Process -FilePath $java -ArgumentList ($jargs | ForEach-Object { if ($_ -match ' ') { '"' + $_ + '"' } else { $_ } }) -WorkingDirectory $here -Wait -PassThru -NoNewWindow
Note "$url -> exit $($p.ExitCode)"
exit $p.ExitCode
