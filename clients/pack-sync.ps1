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

# Steam extras from the pack (library art, Steam Deck controller layout), into this device's Steam shortcut. steam-target.json
# (written by setup) says where. First time: installed, keeping what was there as *.before-pack. After that a file is
# replaced only while it is still the version last installed here, so art or a layout changed in Steam is kept.
# Steam reads both when it starts: a change shows after Steam is next restarted.
$target = Join-Path $here 'steam-target.json'
if (Test-Path $target) {
    try {
        $t = Get-Content $target -Raw | ConvertFrom-Json
        $statePath = Join-Path $here 'steam-applied.json'
        $state = @{}
        if (Test-Path $statePath) { (Get-Content $statePath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $state[$_.Name] = $_.Value } }
        $sha = [Security.Cryptography.SHA256]::Create()
        function H([byte[]]$b) { -join ($sha.ComputeHash($b) | ForEach-Object { $_.ToString('x2') }) }
        $items = @(
            @('steam/art/cover.png', (Join-Path $t.grid "$($t.appid)p.png")),
            @('steam/art/hero.png', (Join-Path $t.grid "$($t.appid)_hero.png")),
            @('steam/art/logo.png', (Join-Path $t.grid "$($t.appid)_logo.png")),
            @('steam/art/icon.png', (Join-Path $t.grid "$($t.appid)_icon.png")),
            @('steam/art/wide.png', (Join-Path $t.grid "$($t.appid).png")),
            @('steam/controller_neptune.vdf', (Join-Path $t.controller 'controller_neptune.vdf')))
        $changed = @()
        foreach ($i in $items) {
            $src = Join-Path $here $i[0]; $dst = $i[1]
            if (-not (Test-Path $src)) { continue }
            $bytes = [IO.File]::ReadAllBytes($src)
            if ($i[0] -like '*.vdf') {
                $text = [Text.Encoding]::UTF8.GetString($bytes) -replace '"url"\s*"[^"]*"', ('"url"' + "`t`t" + '"autosave://' + ($dst -replace '\\', '/') + '"')
                $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($text)
            }
            $new = H $bytes
            $have = if (Test-Path $dst) { H ([IO.File]::ReadAllBytes($dst)) } else { $null }
            $last = $state[$i[0]]
            if ($have -eq $new) { $state[$i[0]] = $new; continue }
            if ($have -and $last -and $have -ne $last) { continue }   # changed in Steam: kept
            if ($have -and -not $last) { Copy-Item $dst "$dst.before-pack" -Force }
            New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
            [IO.File]::WriteAllBytes($dst, $bytes)
            $state[$i[0]] = $new; $changed += (Split-Path $i[0] -Leaf)
        }
        # The layout is only used once Steam's list for this controller type names it.
        $set = Join-Path (Split-Path $t.controller) 'configset_controller_neptune.vdf'
        $key = Split-Path $t.controller -Leaf
        $txt = if (Test-Path $set) { [IO.File]::ReadAllText($set) } else { "`"controller_config`"`n{`n}`n" }
        if ($txt.IndexOf("`"$key`"", [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $end = $txt.LastIndexOf('}')
            $txt = $txt.Substring(0, $end) + "`t`"$key`"`n`t{`n`t`t`"autosave`"`t`t`"1`"`n`t}`n" + $txt.Substring($end)
            [IO.File]::WriteAllText($set, $txt); $changed += 'controller list'
        }
        $state | ConvertTo-Json | Set-Content -Encoding utf8 $statePath
        if ($changed) { Note ("Steam extras updated: " + ($changed -join ', ') + " (shows after Steam restarts)") }
    } catch { Note "Steam extras skipped: $($_.Exception.Message)" }
}
exit $p.ExitCode
