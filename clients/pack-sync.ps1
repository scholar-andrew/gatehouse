# October Journey pack sync (Windows). Prism runs this before Minecraft starts, in the instance's minecraft folder.
# It tries each source in pack-sources.txt in order (this PC over Tailscale first, then GitHub), and brings the instance up
# to date with packwiz-installer: new and updated mods are downloaded and checked, removed ones taken out. A Windows
# Prism instance gets the main pack; Steam Deck-only mods are never installed here.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$variant = 'main'
$sources = Get-Content (Join-Path $here 'pack-sources.txt') | Where-Object { $_ -and -not $_.Trim().StartsWith('#') } | ForEach-Object { $_.Trim().TrimEnd('/') }
$url = $null
foreach ($s in $sources) {
    try {
        $r = Invoke-WebRequest "$s/$variant/pack.toml" -UseBasicParsing -TimeoutSec 3 -Method Head
        if ($r.StatusCode -eq 200) { $url = "$s/$variant/pack.toml"; break }
    } catch { }
}
if (-not $url) { Write-Host 'Pack sync: no source reachable; starting with the mods already installed.'; exit 0 }
Write-Host "Pack sync: $url"
$java = if ($env:INST_JAVA) { $env:INST_JAVA } else { 'java' }
Push-Location $here
try { $extra = @(); if ($env:PACK_SYNC_ARGS) { $extra = $env:PACK_SYNC_ARGS -split ' ' }; & $java -jar (Join-Path $here 'packwiz-installer-bootstrap.jar') @extra $url; $code = $LASTEXITCODE } finally { Pop-Location }
exit $code
