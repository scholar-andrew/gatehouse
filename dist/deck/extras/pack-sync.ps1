# October Journey pack sync (Windows). Prism runs this before Minecraft starts, in the instance's minecraft folder.
# It tries each source in pack-sources.txt in order (this PC over Tailscale first, then GitHub), and brings the instance up
# to date with packwiz-installer: new and updated mods are downloaded and checked, removed ones taken out. A Windows
# Prism instance gets the main pack; Steam Deck-only mods are never installed here. Every run is noted in pack-sync.log.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $here 'pack-sync.log'
function Note($text) { Add-Content -Path $log -Value ("{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $text); Write-Host "Pack sync: $text" }
# A brand-new instance has no mods yet: starting the game then would only show a modless Minecraft that can't join the
# server, so in that case a failed sync stops the launch (Prism shows the error) instead.
function HasMods { [bool](Get-ChildItem (Join-Path $here 'mods') -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1) }
function StopIfNoMods($why, $advice = 'Check your connection (Tailscale or internet) and press Play again.') {
    if (HasMods) { Note "$why; starting with the mods already installed"; exit 0 }
    Note "$why, and no mods are installed yet: stopping the launch. Details: pack-sync.log and pack-sync-java.log in $here"
    Write-Host "October Journey couldn't install its mods yet ($why). $advice"
    exit 1
}
$variant = 'main'
$sources = Get-Content (Join-Path $here 'pack-sources.txt') | Where-Object { $_ -and -not $_.Trim().StartsWith('#') } | ForEach-Object { $_.Trim().TrimEnd('/') }
$url = $null
foreach ($s in $sources) {
    try {
        $r = Invoke-WebRequest "$s/$variant/pack.toml" -UseBasicParsing -TimeoutSec 3 -Method Head
        if ($r.StatusCode -eq 200) { $url = "$s/$variant/pack.toml"; break }
    } catch { }
}
if (-not $url) { StopIfNoMods 'no source reachable' }
# Java: the one Prism hands over (INST_JAVA), else one Prism downloaded, else whatever is on PATH. On an instance's
# first launch Prism hasn't chosen a Java yet and INST_JAVA is just its data folder, so only an actual java(w).exe
# counts. Prism hands over
# javaw.exe, which doesn't report back: the console java.exe beside it is used instead, run directly so this waits for
# it and gets its exit code. Its output goes to pack-sync-java.log.
$java = $null
foreach ($c in @($env:INST_JAVA) + @(Get-ChildItem (Join-Path $here '..\..\..\java') -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' })) {
    if (-not $c) { continue }
    $c = $c -replace '/', '\'
    if ((Split-Path $c -Leaf) -notmatch '^javaw?(\.exe)?$') { continue }
    $console = Join-Path (Split-Path $c -Parent) 'java.exe'
    if (Test-Path -LiteralPath $console -PathType Leaf) { $java = $console; break }
    if (Test-Path -LiteralPath $c -PathType Leaf) { $java = $c; break }
}
if (-not $java -and (Get-Command java -ErrorAction SilentlyContinue)) { $java = (Get-Command java).Source }
if (-not $java) { StopIfNoMods "no Java found yet (INST_JAVA was '$env:INST_JAVA')" 'Prism Launcher is still setting up Java: press Play again.' }
$jargs = @('-jar', (Join-Path $here 'packwiz-installer-bootstrap.jar'))
if ($env:PACK_SYNC_ARGS) { $jargs += $env:PACK_SYNC_ARGS -split ' ' }
$jargs += $url
$javaLog = Join-Path $here 'pack-sync-java.log'
$code = $null
Push-Location $here
try {
    if ($java -like '*javaw.exe') {
        # Only javaw found: started and waited on, without its output.
        $code = (Start-Process -FilePath $java -ArgumentList ($jargs | ForEach-Object { if ($_ -match ' ') { '"' + $_ + '"' } else { $_ } }) -WorkingDirectory $here -Wait -PassThru).ExitCode
    } else {
        $ErrorActionPreference = 'Continue'
        & $java @jargs 2>&1 | ForEach-Object { "$_" } | Set-Content -Encoding utf8 $javaLog
        $code = $LASTEXITCODE
    }
} catch { Note "couldn't run Java ($java): $($_.Exception.Message)" }
Pop-Location
Note "$url -> exit $code (Java: $java)"
if ($code -ne 0) {
    if (Test-Path $javaLog) { Get-Content $javaLog -Tail 5 | ForEach-Object { Note "  java: $_" } }
    if (-not (HasMods)) { StopIfNoMods "the mod download failed (exit $code)" }
}

# Steam extras from the pack (library art, Steam Deck controller layout), into this device's Steam shortcut. steam-target.json
# (written by setup) says where. First time: installed, keeping what was there as *.before-pack. After that a file is
# replaced only while it is still the version last installed here, so art or a layout changed in Steam is kept.
# Steam reads both when it starts: a change shows after Steam is next restarted.
# pack-options.json (written by setup and the uninstaller) says which of these the player wants kept up to date:
# steam_art, steam_controller, server_entry. Missing file or key: yes.
$opt = @{ steam_art = $true; steam_controller = $true; server_entry = $true }
$optPath = Join-Path $here 'pack-options.json'
if (Test-Path $optPath) { try { (Get-Content $optPath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $opt[$_.Name] = [bool]$_.Value } } catch { } }
$target = Join-Path $here 'steam-target.json'
if ((Test-Path $target) -and ($opt.steam_art -or $opt.steam_controller)) {
    try {
        $t = Get-Content $target -Raw | ConvertFrom-Json
        $statePath = Join-Path $here 'steam-applied.json'
        $state = @{}
        if (Test-Path $statePath) { (Get-Content $statePath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $state[$_.Name] = $_.Value } }
        $sha = [Security.Cryptography.SHA256]::Create()
        function Get-PackHash([byte[]]$b) { -join ($sha.ComputeHash($b) | ForEach-Object { $_.ToString('x2') }) }
        $items = @(
            @('steam/art/cover.png', (Join-Path $t.grid "$($t.appid)p.png")),
            @('steam/art/hero.png', (Join-Path $t.grid "$($t.appid)_hero.png")),
            @('steam/art/logo.png', (Join-Path $t.grid "$($t.appid)_logo.png")),
            @('steam/art/icon.png', (Join-Path $t.grid "$($t.appid)_icon.png")),
            @('steam/art/wide.png', (Join-Path $t.grid "$($t.appid).png")),
            @('steam/controller_neptune.vdf', (Join-Path $t.controller 'controller_neptune.vdf')))
        $changed = @()
        foreach ($i in $items) {
            if ($i[0] -like '*.vdf') { if (-not $opt.steam_controller) { continue } } elseif (-not $opt.steam_art) { continue }
            $src = Join-Path $here $i[0]; $dst = $i[1]
            if (-not (Test-Path $src)) { continue }
            $bytes = [IO.File]::ReadAllBytes($src)
            if ($i[0] -like '*.vdf') {
                $text = [Text.Encoding]::UTF8.GetString($bytes) -replace '"url"\s*"[^"]*"', ('"url"' + "`t`t" + '"autosave://' + ($dst -replace '\\', '/') + '"')
                $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($text)
            }
            $new = Get-PackHash $bytes
            $have = if (Test-Path $dst) { Get-PackHash ([IO.File]::ReadAllBytes($dst)) } else { $null }
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
        if ($opt.steam_controller -and $txt.IndexOf("`"$key`"", [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $end = $txt.LastIndexOf('}')
            $txt = $txt.Substring(0, $end) + "`t`"$key`"`n`t{`n`t`t`"autosave`"`t`t`"1`"`n`t}`n" + $txt.Substring($end)
            [IO.File]::WriteAllText($set, $txt); $changed += 'controller list'
        }
        $state | ConvertTo-Json | Set-Content -Encoding utf8 $statePath
        if ($changed) { Note ("Steam extras updated: " + ($changed -join ', ') + " (shows after Steam restarts)") }
    } catch { Note "Steam extras skipped: $($_.Exception.Message)" }
}

# The pack's servers (extras/servers.json) in Minecraft's multiplayer list (servers.dat, uncompressed NBT). A missing one is
# added at the top; a wrong address is corrected while it is still the one this sync last set (or it never set one), so an
# entry edited or deleted in the game afterwards is left alone. Other entries are kept as they are.
$want = Join-Path $here 'extras/servers.json'
if ((Test-Path $want) -and $opt.server_entry) {
    try {
        $servers = @(Get-Content $want -Raw | ConvertFrom-Json | ForEach-Object { $_ })   # (5.1 hands a JSON array over as one item)
        $datPath = Join-Path $here 'servers.dat'
        $sStatePath = Join-Path $here 'servers-applied.json'
        $sState = @{}
        if (Test-Path $sStatePath) { (Get-Content $sStatePath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $sState[$_.Name] = $_.Value } }
        $d = if (Test-Path $datPath) { [IO.File]::ReadAllBytes($datPath) } else { $null }
        $script:i = 0
        function U16 { $v = ([int]$d[$script:i] -shl 8) -bor $d[$script:i + 1]; $script:i += 2; $v }
        function I32 { $v = ([int]$d[$script:i] -shl 24) -bor ([int]$d[$script:i + 1] -shl 16) -bor ([int]$d[$script:i + 2] -shl 8) -bor $d[$script:i + 3]; $script:i += 4; $v }
        function Str { $n = U16; $v = [Text.Encoding]::UTF8.GetString($d, $script:i, $n); $script:i += $n; $v }
        function Skip($t) {
            switch ($t) {
                1 { $script:i += 1 } 2 { $script:i += 2 } 3 { $script:i += 4 } 4 { $script:i += 8 } 5 { $script:i += 4 } 6 { $script:i += 8 }
                7 { $n = I32; $script:i += $n } 8 { $n = U16; $script:i += $n }
                9 { $et = $d[$script:i]; $script:i++; $n = I32; for ($k = 0; $k -lt $n; $k++) { Skip $et } }
                10 { while (($tt = $d[$script:i]) -ne 0) { $script:i++; [void](Str); Skip $tt }; $script:i++ }
                11 { $n = I32; $script:i += 4 * $n } 12 { $n = I32; $script:i += 8 * $n }
                default { throw "unknown NBT tag $t" }
            }
        }
        function SPay([string]$s) { $b = [Text.Encoding]::UTF8.GetBytes($s); , [byte[]](@([byte]($b.Length -shr 8), [byte]($b.Length -band 255)) + $b) }
        function PStr($p) { [Text.Encoding]::UTF8.GetString([byte[]]$p, 2, $p.Count - 2) }
        $rootOther = New-Object System.Collections.ArrayList   # raw root tags other than the list
        $entries = New-Object System.Collections.ArrayList     # each: list of @(tag type, name, payload bytes)
        if ($d) {
            if ($d[0] -ne 10) { throw 'servers.dat is not plain NBT' }
            $script:i = 1; [void](Str)
            while (($tt = $d[$script:i]) -ne 0) {
                $start = $script:i; $script:i++; $nm = Str
                if ($tt -eq 9 -and $nm -eq 'servers') {
                    $et = $d[$script:i]; $script:i++; $n = I32
                    if ($n -gt 0 -and $et -ne 10) { throw 'unexpected server list' }
                    for ($k = 0; $k -lt $n; $k++) {
                        $fields = New-Object System.Collections.ArrayList
                        while (($ft = $d[$script:i]) -ne 0) { $script:i++; $fn = Str; $ps = $script:i; Skip $ft; [void]$fields.Add(@($ft, $fn, [byte[]]$d[$ps..($script:i - 1)])) }
                        $script:i++; [void]$entries.Add($fields)
                    }
                } else { Skip $tt; [void]$rootOther.Add([byte[]]$d[$start..($script:i - 1)]) }
            }
        }
        $changes = @()
        foreach ($s in $servers) {
            $e = $null; $ipf = $null
            foreach ($x in $entries) { foreach ($f in $x) { if ($f[0] -eq 8 -and $f[1] -eq 'name' -and (PStr $f[2]) -eq $s.name) { $e = $x } }; if ($e) { break } }
            if (-not $e) { foreach ($x in $entries) { foreach ($f in $x) { if ($f[0] -eq 8 -and $f[1] -eq 'ip' -and (PStr $f[2]) -eq $s.address) { $e = $x } }; if ($e) { break } } }
            $last = $sState[$s.name]
            if (-not $e) {
                if ($last) { continue }   # deleted in the game: left out
                $nf = New-Object System.Collections.ArrayList
                [void]$nf.Add(@(8, 'name', (SPay $s.name))); [void]$nf.Add(@(8, 'ip', (SPay $s.address)))
                $entries.Insert(0, $nf); $changes += "added $($s.name) ($($s.address))"; $sState[$s.name] = $s.address; continue
            }
            foreach ($f in $e) { if ($f[1] -eq 'ip') { $ipf = $f } }
            $ip = if ($ipf) { PStr $ipf[2] } else { '' }
            if ($ip -eq $s.address) { $sState[$s.name] = $s.address; continue }
            if ($last -and $ip -ne $last) { continue }   # changed in the game: kept
            if ($ipf) { $ipf[2] = SPay $s.address } else { [void]$e.Add(@(8, 'ip', (SPay $s.address))) }
            $changes += "$($s.name) address $ip -> $($s.address)"; $sState[$s.name] = $s.address
        }
        if ($changes) {
            $ms = New-Object IO.MemoryStream
            function W([byte[]]$b) { $ms.Write($b, 0, $b.Length) }
            W ([byte[]](10, 0, 0))
            foreach ($r in $rootOther) { W $r }
            $n = $entries.Count
            W ([byte[]](9)); W (SPay 'servers'); W ([byte[]](10, (($n -shr 24) -band 255), (($n -shr 16) -band 255), (($n -shr 8) -band 255), ($n -band 255)))
            foreach ($x in $entries) { foreach ($f in $x) { W ([byte[]]@($f[0])); W (SPay $f[1]); W $f[2] }; W ([byte[]](0)) }
            W ([byte[]](0))
            if ((Test-Path $datPath) -and -not (Test-Path "$datPath.before-pack")) { Copy-Item $datPath "$datPath.before-pack" }
            [IO.File]::WriteAllBytes($datPath, $ms.ToArray())
            Note ("server list: " + ($changes -join '; '))
        }
        $sState | ConvertTo-Json | Set-Content -Encoding utf8 $sStatePath
    } catch { Note "server list skipped: $($_.Exception.Message)" }
}
# This script, from the pack: the next launch runs the pack's copy. (PowerShell has already read this file, so replacing
# it now is safe.)
$next = Join-Path $here 'extras/pack-sync.ps1'
$self = $MyInvocation.MyCommand.Path
if ((Test-Path $next) -and (Get-FileHash $next).Hash -ne (Get-FileHash $self).Hash) {
    try { Copy-Item $next "$self.new" -Force; Move-Item "$self.new" $self -Force; Note 'sync script updated (used from the next launch)' }
    catch { Note "sync script update skipped: $($_.Exception.Message)" }
}
exit 0
