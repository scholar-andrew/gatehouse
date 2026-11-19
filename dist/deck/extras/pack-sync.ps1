# October Journey pack sync (Windows). Prism runs this before Minecraft starts, in the instance's minecraft folder.
# It tries each source in pack-sources.txt in order (this PC over Tailscale first, then GitHub), and brings the instance up
# to date with packwiz-installer: new and updated mods are downloaded and checked, removed ones taken out. A Windows
# Prism instance gets the main pack; Steam Deck-only mods are never installed here. Every run is noted in pack-sync.log.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
# The pack's name, from its profile (extras/profile.json, shipped with the pack): nothing about a server is fixed here.
$packName = 'The pack'
try { $n = (Get-Content (Join-Path $here 'extras/profile.json') -Raw -ErrorAction Stop | ConvertFrom-Json).name; if ($n) { $packName = $n } } catch { }
$log = Join-Path $here 'pack-sync.log'
# R100: log lines start with the unix time (milliseconds since 1970, UTC), then this machine's time for reading.
function Stamp { '{0} {1}' -f [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(), (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') }
function Note($text) { Add-Content -Path $log -Value ("{0}  {1}" -f (Stamp), $text); Write-Host "Pack sync: $text" }
# For the pop-up: what this launch checked and how it went (pack-sync-status.txt, started afresh each launch), one line
# each: "check|<ok, updated, off, warn or failed>|<what>|<detail>", "switch|<on or off>|<mod>" or "note|<text>". And
# packwiz-installer's record of the installed files as it was before this launch's update (packwiz-before.json; "{}" on
# a first install), which the pop-up compares with the record afterwards to list the mods added, removed and updated.
$statusFile = Join-Path $here 'pack-sync-status.txt'
# Run by the Gatehouse launcher instead of Prism (GATEHOUSE_EMBED=1): its own window is the checklist, so no window is
# opened from here; the question about the download is asked there; and at the end, instead of waiting for Play, this
# leaves "pack-sync-fresh.txt" (when, and how it went) and ends. Gatehouse then has Prism start the instance, which
# runs this script again in the usual way: finding that note less than three minutes old, it updates nothing a second
# time and goes straight to the launcher window, with what the first run found.
$embed = [bool]$env:GATEHOUSE_EMBED
$freshFile = Join-Path $here 'pack-sync-fresh.txt'
$fresh = $null
if (-not $embed -and (Test-Path $freshFile)) {
    try { $ff = "$(Get-Content $freshFile -Raw)".Trim() -split '\|'; $age = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long]$ff[0]; if ($age -ge 0 -and $age -lt 180 -and $ff[1]) { $fresh = $ff[1] } } catch { }
}
Remove-Item $freshFile -Force -ErrorAction SilentlyContinue
if (-not $fresh) {
Set-Content -Path $statusFile -Value $null -Encoding utf8
Remove-Item (Join-Path $here 'pack-sync-news.txt') -ErrorAction SilentlyContinue   # (what syncs before this one wrote)
}
function Status($state, $what, $detail = '') { Add-Content -Path $statusFile -Value "check|$state|$what|$detail" -Encoding utf8 }
function News($text) { Add-Content -Path $statusFile -Value "note|$text" -Encoding utf8 }
$pwRecord = Join-Path $here 'packwiz.json'
if ($fresh) { }   # (the records are the first run's: kept)
elseif (Test-Path $pwRecord) { Copy-Item $pwRecord (Join-Path $here 'packwiz-before.json') -Force } else { Set-Content (Join-Path $here 'packwiz-before.json') '{}' -Encoding ascii }
# The pop-up (extras/launch-popup.jar, a small Java window): started first thing as a checklist of this script's steps,
# ticked off as their "check" lines arrive in pack-sync-status.txt, then (after "done|<status>") the summary in the same
# window: what changed, patch notes, the Server tab, and Play, Restart (run this again) or Quit (cancel the launch).
# Prism waits for it. javaw, so no console window opens. Its exit code says what the player chose: 0 play, 10 quit,
# 11 restart.
$popupJar = Join-Path $here 'extras/launch-popup.jar'
$script:popup = $null
# The launcher runs from a copy (launcher-run.jar, beside this script): Windows locks a running program's file, and the
# update has to be able to replace extras/launch-popup.jar while the launcher's window is open.
$runJar = Join-Path $here 'launcher-run.jar'
function RunJar {
    try { Copy-Item -LiteralPath $popupJar -Destination $runJar -Force -ErrorAction Stop } catch { }
    if (Test-Path $runJar) { $runJar } else { $popupJar }
}
# Does this device's launcher know --pack-signed (R40: 3.31.0 or newer)? Read from the notes inside it, never by running
# it: an older one would take the unknown word for something else.
function PackCheckable($min = '3.31.0') {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $z = [IO.Compression.ZipFile]::OpenRead($popupJar)
        try { $e = $z.GetEntry('gatehouse-notes.json'); if (-not $e) { return $false }; $j = (New-Object IO.StreamReader($e.Open())).ReadToEnd() | ConvertFrom-Json } finally { $z.Dispose() }
        return ([version]("$($j.version)" -replace '[^0-9.].*$', '') -ge [version]$min)
    } catch { return $false }
}
# R40, step 2: Gatehouse fetching the update itself (3.32.0 or newer): every source asked at once, the newest pack that
# answers well taken (between equals, the fastest), and every file checked against the signed list before the installer
# gets it. Started in the background; its address is in pack-relay-port.txt. Null when it couldn't be started.
function StartRelay {
    $portFile = Join-Path $here 'pack-relay-port.txt'
    Remove-Item $portFile, (Join-Path $here 'pack-relay-used.txt') -Force -ErrorAction SilentlyContinue
    $jw = Join-Path (Split-Path $java -Parent) 'javaw.exe'; if (-not (Test-Path -LiteralPath $jw)) { $jw = $java }
    $a = @('-jar', (RunJar), '--pack-relay', $variant, $here) + @($sources)
    $quoted = $a | ForEach-Object { if ($_ -match ' ') { '"' + $_ + '"' } else { $_ } }
    try { $p = Start-Process -FilePath $jw -ArgumentList $quoted -WorkingDirectory $here -WindowStyle Hidden -PassThru } catch { return $null }
    for ($i = 0; $i -lt 50; $i++) {
        if (Test-Path $portFile) { $port = "$(Get-Content $portFile -Raw)".Trim(); if ($port -match '^\d+$') { return @{ proc = $p; url = "http://127.0.0.1:$port/pack.toml" } } }
        Start-Sleep -Milliseconds 200
    }
    try { $p.Kill() } catch { }
    return $null
}
function StartProgress {
    if ($embed) { return }   # (the Gatehouse window is the checklist)
    if (-not $java -or -not (Test-Path $popupJar)) { return }
    $w = Join-Path (Split-Path $java -Parent) 'javaw.exe'; if (-not (Test-Path -LiteralPath $w)) { $w = $java }
    try { $script:popup = Start-Process -FilePath $w -ArgumentList @('-jar', "`"$(RunJar)`"", '--progress', "`"$here`"") -PassThru; $null = $script:popup.Handle }
    catch { Note "progress window skipped: $($_.Exception.Message)" }
}
function StopProgress { if ($script:popup -and -not $script:popup.HasExited) { try { $script:popup.Kill() } catch { } } }
function ShowPopup($status) {
    if ($embed) {
        Add-Content -Path $statusFile -Value "done|$status" -Encoding utf8
        Set-Content -Path $freshFile -Value ("{0}|{1}" -f [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(), $status) -Encoding ascii
        return 0
    }
    $p = $script:popup
    if ($p -and -not $p.HasExited) { Add-Content -Path $statusFile -Value "done|$status" -Encoding utf8 }
    elseif ($p -and $p.ExitCode -eq 10) { return 10 }   # quit while the update ran
    else {
        if (-not $java -or -not (Test-Path $popupJar)) { return 0 }
        $w = Join-Path (Split-Path $java -Parent) 'javaw.exe'; if (-not (Test-Path -LiteralPath $w)) { $w = $java }
        try { $p = Start-Process -FilePath $w -ArgumentList @('-jar', "`"$(RunJar)`"", "`"$here`"", $status) -PassThru; $null = $p.Handle }
        catch { Note "pop-up skipped: $($_.Exception.Message)"; return 0 }
    }
    # Waits for the player: nothing starts the game but Play (no time limit).
    $p.WaitForExit()
    return $p.ExitCode
}
# Quit: no game this time (Prism then reports the pre-launch step failed). Restart: this script again, from the top
# (the newest copy, if it just updated itself).
# Quit with the launch wrapper set up for this instance (instance.cfg has it as Prism's wrapper command): this step
# ends well and leaves "launch-cancelled" for the wrapper, which then starts nothing, so Prism sees a launch that ended
# normally. Without the wrapper the only way to stop the launch is to fail this step (Prism then shows its log).
$cancelMark = Join-Path $here 'launch-cancelled'
Remove-Item $cancelMark -Force -ErrorAction SilentlyContinue
# GATEHOUSE_PLAY: the note says "play" when the update AND the launcher window were both in the Gatehouse window, and
# Play was pressed there. Nothing is left to do or show here: the game starts.
if ($fresh -eq 'play') { Note 'updated and Play pressed in the Gatehouse window: starting the game'; exit 0 }
function CancelLaunch {
    $icfg = Join-Path (Split-Path $here -Parent) 'instance.cfg'
    if ((Test-Path (Join-Path $here 'launch-wrapper.exe')) -and (Test-Path $icfg) -and (Select-String -LiteralPath $icfg -Pattern '^WrapperCommand=.*launch-wrapper' -Quiet)) {
        Set-Content -Path $cancelMark -Value 'quit' -Encoding ascii
        exit 0
    }
    exit 1
}
function AfterPopup($c) {
    if ($c -eq 10) { Note 'launch cancelled in the pop-up (Quit)'; CancelLaunch }
    if ($c -eq 11) {
        Note 'running the update again (Restart in the pop-up)'
        $p = Start-Process powershell -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"") -WindowStyle Hidden -Wait -PassThru
        exit $p.ExitCode
    }
}
# A brand-new instance has no mods yet: starting the game then would only show a modless Minecraft that can't join the
# server, so in that case a failed sync stops the launch (Prism shows the error) instead.
function HasMods { [bool](Get-ChildItem (Join-Path $here 'mods') -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1) }
# Safety net: an unexpected error anywhere below never stops a launch that already has its mods (Prism would refuse to
# start the game on a non-zero exit); it's noted, and the game starts with what's installed.
trap {
    try { Add-Content -Path $log -Value ("{0}  unexpected error: {1}" -f (Stamp), $_.Exception.Message) } catch { }
    StopProgress
    if (HasMods) { exit 0 } else { exit 1 }
}
# Mods the player can switch on and off (Mod Menu > October Journey; the list and the pack's defaults are in
# extras/mod-toggles.json, the player's choices under "mods" in pack-options.json). A mod that's off has its jar renamed
# to .jar.disabled after each update (as Prism does, so the game doesn't load it), and renamed back before the next
# update so packwiz-installer finds it intact. mods-switched-off.json remembers which jars this did.
$switchState = Join-Path $here 'mods-switched-off.json'
function RestoreSwitchedOff {
    if (-not (Test-Path $switchState)) { return }
    foreach ($jar in @(Get-Content $switchState -Raw | ConvertFrom-Json | ForEach-Object { $_ })) {
        if ((Test-Path -LiteralPath "$jar.disabled") -and -not (Test-Path -LiteralPath $jar)) { Move-Item -LiteralPath "$jar.disabled" $jar }
    }
    # The list stays until ApplySwitches, which compares with it so only real changes are noted.
}
function ApplySwitches {
    try {
        $tp = Join-Path $here 'extras/mod-toggles.json'; $pw = Join-Path $here 'packwiz.json'
        if (-not (Test-Path $tp) -or -not (Test-Path $pw)) { return }
        $toggles = @(Get-Content $tp -Raw | ConvertFrom-Json | ForEach-Object { $_ })
        $files = (Get-Content $pw -Raw | ConvertFrom-Json).cachedFiles
        $choices = $null
        $po = Join-Path $here 'pack-options.json'
        if (Test-Path $po) { $choices = (Get-Content $po -Raw | ConvertFrom-Json).mods }
        # What each mod should be: the player's choice, else the pack's default; and off whenever a mod it needs is off.
        $want = @{}
        foreach ($t in $toggles) { $want[$t.key] = [bool]$t.default; if ($choices -and $null -ne $choices.($t.key)) { $want[$t.key] = [bool]$choices.($t.key) } }
        for ($pass = 0; $pass -lt $toggles.Count; $pass++) {
            foreach ($t in $toggles) { foreach ($n in @($t.needs)) { if ($n -and $want.ContainsKey($n) -and -not $want[$n]) { $want[$t.key] = $false } } }
        }
        $off = @(); $changed = @()
        $before = @(); if (Test-Path $switchState) { $before = @(Get-Content $switchState -Raw | ConvertFrom-Json | ForEach-Object { $_ }) }
        foreach ($t in $toggles) {
            $entry = $files.($t.file)
            if (-not $entry) { continue }
            $loc = if ($entry.cachedLocation) { $entry.cachedLocation } else { $t.file }
            $jar = Join-Path $here ($loc -replace '/', '\')
            if (-not $want[$t.key]) {
                if (Test-Path -LiteralPath $jar) {
                    if (Test-Path -LiteralPath "$jar.disabled") { Remove-Item -LiteralPath "$jar.disabled" }
                    Move-Item -LiteralPath $jar "$jar.disabled"; if ($before -notcontains $jar) { $changed += "$($t.name) off" }
                }
                if (Test-Path -LiteralPath "$jar.disabled") { $off += $jar }
            } elseif ((Test-Path -LiteralPath "$jar.disabled") -and -not (Test-Path -LiteralPath $jar)) {
                Move-Item -LiteralPath "$jar.disabled" $jar; $changed += "$($t.name) on"
            } elseif ($before -contains $jar) { $changed += "$($t.name) on" }
        }
        if ($off) { ConvertTo-Json @($off) | Set-Content -Encoding utf8 $switchState } elseif (Test-Path $switchState) { Remove-Item $switchState }
        if ($changed) {
            Note ("mods switched: " + ($changed -join ', '))
            foreach ($c in $changed) { Add-Content -Path $statusFile -Value ("switch|{0}|{1}" -f $(if ($c.EndsWith(' on')) { 'on' } else { 'off' }), $c.Substring(0, $c.LastIndexOf(' '))) -Encoding utf8 }
            Status 'updated' 'Mod switches' ("{0} changed; {1} off" -f $changed.Count, $off.Count)
        } else { Status 'ok' 'Mod switches' $(if ($off) { "As set; $($off.Count) off" } else { 'All on' }) }
    } catch { Note "mod switches skipped: $($_.Exception.Message)"; Status 'failed' 'Mod switches' $_.Exception.Message }
}
# Defaults the pack added later, applied once to devices that already had the setting (a new install gets them from the
# pack's config-defaults). pack-defaults-applied.txt lists the ones done; after that the player's own choice stays.
#   ipn-hotbar-icons-off  Inventory Profiles Next's Auto Refill arrows on the hotbar hidden (pack 1.5.0 default)
function ApplyDefaultsOnce {
    $done = Join-Path $here 'pack-defaults-applied.txt'
    $have = @(); if (Test-Path $done) { $have = @(Get-Content $done) }
    if ($have -contains 'ipn-hotbar-icons-off') { return }
    try {
        $f = Join-Path $here 'config\inventoryprofilesnext\inventoryprofiles.json'
        if (Test-Path $f) {
            Add-Type -AssemblyName System.Web.Extensions
            $js = New-Object System.Web.Script.Serialization.JavaScriptSerializer
            $o = $js.DeserializeObject([IO.File]::ReadAllText($f))
            if (-not $o.ContainsKey('AutoRefillSettings')) { $o['AutoRefillSettings'] = @{} }
            $o['AutoRefillSettings']['auto_refill_enable_horbar_indicator_icons'] = @{ value = $false }
            [IO.File]::WriteAllText($f, $js.Serialize($o))
            Note 'pack default applied once: Inventory Profiles Next hotbar arrows hidden'
            News 'Inventory Profiles Next: the purple Auto Refill arrows on the hotbar are now hidden (the pack default; change it in that mod''s settings)'
        }
        Add-Content -Path $done -Value 'ipn-hotbar-icons-off'
    } catch { Note "pack default skipped: $($_.Exception.Message)" }
}
function StopIfNoMods($why, $advice = 'Check your connection (Tailscale or internet) and press Play again.', $status = 'failed') {
    if (HasMods) { ApplySwitches; ApplyDefaultsOnce; Note "$why; starting with the mods already installed"; AfterPopup (ShowPopup $status); exit 0 }
    Note "$why, and no mods are installed yet: stopping the launch. Details: pack-sync.log and pack-sync-java.log in $here"
    Write-Host "$packName couldn't install its mods yet ($why). $advice"
    StopProgress
    exit 1
}
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
if ($fresh) { Note "updated a moment ago in the Gatehouse window ($fresh): straight to the launcher"; AfterPopup (ShowPopup $fresh); exit 0 }
StartProgress
$variant = 'main'
$sources = @(Get-Content (Join-Path $here 'pack-sources.txt') | Where-Object { $_ -and -not $_.Trim().StartsWith('#') } | ForEach-Object { $_.Trim().TrimEnd('/') })
# Which sources answer, in order, each given 5 seconds. If none does (after sleep or at startup the network can still be
# coming up), one more round after 5 seconds.
# To begin with only the first one that answers is looked for (the others are asked only if the update from it fails):
# every launch waited on all of them before.
function Reachable($firstOnly) {
    foreach ($s in $sources) {
        try { $r = Invoke-WebRequest "$s/$variant/pack.toml" -UseBasicParsing -TimeoutSec 5 -Method Head; if ($r.StatusCode -eq 200) { "$s/$variant/pack.toml"; if ($firstOnly) { return } } } catch { }
    }
}
$urls = @(Reachable $true)
if (-not $urls) { Note 'no source answered; trying again in 5 seconds'; Start-Sleep -Seconds 5; $urls = @(Reachable $true) }
if (-not $urls) { Status 'warn' 'Connection' 'No pack source answered (Tailscale or internet)'; Status 'warn' 'Mods and settings' "Not checked; playing what's installed"; StopIfNoMods 'no source reachable' -status 'offline' }
if (-not $java) { StopIfNoMods "no Java found yet (INST_JAVA was '$env:INST_JAVA')" 'Prism Launcher is still setting up Java: press Play again.' }
# packwiz-installer (the program the bootstrap runs) is pinned to one release, so it only changes when the pack changes
# it. Once that release is here (packwiz-installer.jar's manifest says so), the bootstrap doesn't ask GitHub at all:
# one less connection that can fail, and no GitHub rate limit. Otherwise it fetches exactly that release.
$installerTag = 'v0.5.14'
$installerRelease = "https://api.github.com/repos/packwiz/packwiz-installer/releases/tags/$installerTag"
function InstallerHere {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $z = [IO.Compression.ZipFile]::OpenRead((Join-Path $here 'packwiz-installer.jar'))
        try { $m = (New-Object IO.StreamReader($z.GetEntry('META-INF/MANIFEST.MF').Open())).ReadToEnd() } finally { $z.Dispose() }
        return [bool]($m -match ('Implementation-Version:\s*' + [regex]::Escape($installerTag) + '\s'))
    } catch { return $false }
}
$javaLog = Join-Path $here 'pack-sync-java.log'
# One run of packwiz-installer against one source. Stopped if it hasn't finished in 5 minutes, since a stalled download
# would otherwise hold the launch forever. Returns its exit code, 'timeout' or 'error'. Its output goes to
# pack-sync-java.log.
function RunInstaller($u) {
    # R40: the pack at this source has to be the published one. Its pack.toml's release signature is checked first (by
    # the launcher; only one from 3.31.0 on knows how, so an older one isn't asked). A signature that doesn't fit skips
    # this source and the next is tried; none found yet (GitHub signs a minute or two after each publish) is taken.
    if ($u -notmatch '^http://127\.0\.0\.1:' -and (PackCheckable)) {
        $chk = "$(& $java -jar (RunJar) --pack-signed $u 2>$null | Select-Object -First 1)"
        Note "pack signature at ${u}: $chk"
        if ($chk -like 'bad*') { Status 'warn' 'Connection' "A source's pack isn't the published one, so it was skipped"; return 'bad-signature' }
    }
    # -g: no windows. Otherwise a failed download waits on an error dialog (Quit / Continue) for someone to click,
    # holding the launch; without them a failure just ends the run, and the next source is tried.
    $a = @('-jar', (Join-Path $here 'packwiz-installer-bootstrap.jar'), '-g')
    if (InstallerHere) { $a += '--bootstrap-no-update' } else { $a += @('--bootstrap-update-url', $installerRelease) }
    if ($env:PACK_SYNC_ARGS) { $a += $env:PACK_SYNC_ARGS -split ' ' }
    $a += $u
    $quoted = $a | ForEach-Object { if ($_ -match ' ') { '"' + $_ + '"' } else { $_ } }
    try {
        # javaw with no window: java.exe would open a console window for each run.
        $jw = Join-Path (Split-Path $java -Parent) 'javaw.exe'; if (-not (Test-Path -LiteralPath $jw)) { $jw = $java }
        $p = Start-Process -FilePath $jw -ArgumentList $quoted -WorkingDirectory $here -WindowStyle Hidden -PassThru -RedirectStandardOutput $javaLog -RedirectStandardError "$javaLog.err"
        $null = $p.Handle   # (so the exit code is kept)
        $limit = if ($env:PACK_SYNC_TIMEOUT) { [int]$env:PACK_SYNC_TIMEOUT } else { 300 }   # seconds; the variable is for testing
        if (-not $p.WaitForExit($limit * 1000)) { try { $p.Kill() } catch { }; return 'timeout' }
        $p.WaitForExit()
        return $p.ExitCode
    } catch { Note "couldn't run Java ($java): $($_.Exception.Message)"; return 'error' }
    # Its error output (kept apart by Start-Process) goes on the end of the log; the .err file is reused next run.
    finally { Get-Content "$javaLog.err" -ErrorAction SilentlyContinue | Add-Content $javaLog -ErrorAction SilentlyContinue }
}
# Before anything is downloaded the launcher says how big the update is and asks: Download, Skip this time or Quit.
# "ask|<pack address>" in the status file starts it; the launcher works the size out from the pack's update-sizes.json
# against what's installed here, and answers in pack-sync-answer.txt: "wait" while it's asking, then "go" or
# "skip|<size>" (Quit closes it: exit code 10). With nothing to download it answers "go" at once. No launcher window,
# or no answer begun within 30 seconds: no question, the update just runs.
# The launcher updates itself first ("self-update" on the ask line says this script can handle it): if the pack has a
# newer launch-popup.jar, it fetches and checks it, leaves it as launch-popup.jar.new and closes with exit code 12;
# this script swaps it in and opens the new launcher, which then answers the same question. One such round at most.
$answerFile = Join-Path $here 'pack-sync-answer.txt'
Remove-Item $answerFile, "$popupJar.new" -Force -ErrorAction SilentlyContinue
$skip = $false; $skipSize = ''
# Nothing to update: the launcher compares the pack's file list with what's installed here anyway (for the size of the
# download), and when every file is here and none is left over it answers "current" ("current-ok" on the ask line says
# this script understands that). packwiz-installer then isn't run at all, which is most of what a launch with no
# update used to wait for. As a safety net it is still run in full when it hasn't been for a week
# (pack-sync-full.txt: when it last was).
$current = $false
$fullFile = Join-Path $here 'pack-sync-full.txt'
$recentFull = $false
try { $fa = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - [long]("$(Get-Content $fullFile -Raw -ErrorAction Stop)".Trim()); $recentFull = ($fa -ge 0 -and $fa -lt 7 * 86400) } catch { }
foreach ($round in 1, 2) {
    if (-not $embed -and -not ($script:popup -and -not $script:popup.HasExited)) { break }
    # (Gatehouse's window updates Gatehouse itself, in its own time: no "self-update" for it)
    if ($round -eq 1) { Add-Content -Path $statusFile -Value ('ask|' + ($urls[0] -replace '/pack\.toml$', '') + $(if ($embed) { '|' } else { '|self-update' }) + '|current-ok') -Encoding utf8 }
    $asked = Get-Date; $answer = ''
    while ($embed -or -not $script:popup.HasExited) {
        $answer = if (Test-Path $answerFile) { "$(Get-Content $answerFile -Raw -ErrorAction SilentlyContinue)".Trim() } else { '' }
        if ($answer -and $answer -ne 'wait') { break }
        if (-not $answer -and ((Get-Date) - $asked).TotalSeconds -gt 30) { Note 'the launcher gave no answer about the update size; updating'; break }
        Start-Sleep -Milliseconds 50
    }
    if ($embed) { if ($answer -eq 'quit') { Note 'launch cancelled in the Gatehouse window (Quit)'; exit 10 } }
    elseif ($script:popup.HasExited -and $script:popup.ExitCode -eq 10) { AfterPopup 10 }
    if (-not $embed -and $round -eq 1 -and $script:popup.HasExited -and $script:popup.ExitCode -eq 12 -and (Test-Path "$popupJar.new")) {
        try { Move-Item "$popupJar.new" $popupJar -Force; Note 'the launcher updated itself; opening the new one' }
        catch { Note "couldn't swap in the new launcher: $($_.Exception.Message)" }
        Remove-Item $answerFile -Force -ErrorAction SilentlyContinue
        StartProgress
        continue
    }
    if ($answer -eq 'current' -and $recentFull) { $current = $true; Note 'nothing to update: every file of the pack is already here' }
    if ($answer -like 'skip*') { $skip = $true; $skipSize = "$(($answer -split '\|', 2)[1])".Trim(); Note "update skipped in the launcher (about $skipSize waiting)" }
    break
}
RestoreSwitchedOff
# Each source that answered, in order, then the first once more: an update that fails or stalls (say the connection
# dropped part-way) is tried again. packwiz-installer only replaces files it has fully downloaded and checked, and
# picks up where it left off on the next try.
$code = $null; $url = $null
if ($skip -or $current) { $code = 0; $url = $urls[0] }
$queue = New-Object System.Collections.ArrayList
$relayUsed = $null
if (-not ($skip -or $current)) {
    # (through Gatehouse first, where it can; if that doesn't finish, the sources one by one, as before)
    $relay = if (PackCheckable '3.32.0') { StartRelay } else { $null }
    if ($relay) {
        $jarFile = Join-Path $here 'extras\launch-popup.jar'
        $jarWas = try { (Get-FileHash -LiteralPath $jarFile -Algorithm SHA256).Hash } catch { '' }
        $code = RunInstaller $relay.url
        Note "through Gatehouse ($($relay.url)) -> exit $code"
        if (Test-Path (Join-Path $here 'pack-relay.log')) { Get-Content (Join-Path $here 'pack-relay.log') -Tail 3 | ForEach-Object { Note "  relay: $_" } }
        try { $relay.proc.Kill() } catch { }
        $used = @(Get-Content (Join-Path $here 'pack-relay-used.txt') -ErrorAction SilentlyContinue)
        if ("$code" -eq '0' -and $used.Count -ge 2) {
            $url = $used[0] + '/pack.toml'; $relayUsed = $used[1]
            # (R40: Gatehouse itself came with this update from another Gate System: noted, for the update log)
            if ($relayUsed -eq 'peer') { try { $jarNow = (Get-FileHash -LiteralPath $jarFile -Algorithm SHA256).Hash
                if ($jarNow -and $jarNow -ne $jarWas) { Set-Content -LiteralPath (Join-Path $here 'extras\launch-popup.source.txt') -Value "$($jarNow.ToLower())|another Gate System ($(([uri]$used[0]).Host))" -Encoding ascii } } catch { } }
        }
        else { $code = $null; [void]$queue.Add($urls[0]) }
    } else { [void]$queue.Add($urls[0]) }
}
$widened = $false
while ($queue.Count -gt 0) {
    $u = $queue[0]; $queue.RemoveAt(0)
    $code = RunInstaller $u; $url = $u
    Note "$u -> exit $code (Java: $java)"
    if ("$code" -eq '0') { break }
    if (Test-Path $javaLog) { Get-Content $javaLog -Tail 5 | ForEach-Object { Note "  java: $_" } }
    # (it failed: now the other sources that answer, then the first once more)
    if (-not $widened) { $widened = $true; foreach ($m in @(Reachable $false)) { if ($m -ne $urls[0]) { [void]$queue.Add($m) } }; [void]$queue.Add($urls[0]) }
}
if ("$code" -eq '0' -and -not $skip -and -not $current) { Set-Content -Path $fullFile -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) -Encoding ascii }
$where = if ($url -match 'githubusercontent') { 'GitHub' } elseif ($relayUsed -eq 'peer') { "another Gate System ($(([uri]$url).Host))" } else { "the owner's PC (Tailscale)" }
if ($skip) {
    Status 'ok' 'Connection' $where
    Status 'warn' 'Mods and settings' "Update skipped this time (about $skipSize waiting); playing what's installed"
} elseif ("$code" -eq '0') {
    Status 'ok' 'Connection' $where
    Status 'ok' 'Mods and settings'   # (the pop-up adds what changed, from the records)
} else {
    Status 'warn' 'Connection' "$where; the update didn't finish"
    Status 'failed' 'Mods and settings' "Didn't finish (exit $code); playing what's installed. Details: pack-sync.log"
}
if ("$code" -ne '0' -and -not (HasMods)) { StopIfNoMods "the mod download failed (exit $code)" }
ApplySwitches
ApplyDefaultsOnce
# Where the pack is fetched from follows its profile (extras/profile.json "pack_sources"): pack-sources.txt is rewritten
# to match, for the next launch. Only after an update that worked, and only when the new list still has the source that
# just worked in it, so a wrong list can never leave a device with no source that answers.
try {
    if ("$code" -eq '0' -and -not $skip) {
        $want = @((Get-Content (Join-Path $here 'extras/profile.json') -Raw -ErrorAction Stop | ConvertFrom-Json).pack_sources | Where-Object { $_ } | ForEach-Object { "$_".Trim().TrimEnd('/') })
        $worked = $url -replace "/$variant/pack\.toml$", ''
        if ($want.Count -gt 0 -and ($want -contains $worked) -and (($want -join '|') -ne (@($sources) -join '|'))) {
            Set-Content -Path (Join-Path $here 'pack-sources.txt') -Value (@("# Tried in order; the first that answers is used. (Kept up to date from the pack's profile.)") + $want) -Encoding ascii
            Note ("pack sources updated from the profile: " + ($want -join ', '))
        }
    }
} catch { }
# The launch wrapper, kept beside this script (Prism runs it from there; see CancelLaunch above).
try {
    $ws = Join-Path $here 'extras/launch-wrapper.exe'; $wd = Join-Path $here 'launch-wrapper.exe'
    if ((Test-Path $ws) -and (-not (Test-Path $wd) -or (Get-FileHash $ws).Hash -ne (Get-FileHash $wd).Hash)) { Copy-Item $ws $wd -Force }
} catch { Note "launch wrapper not updated: $($_.Exception.Message)" }

# Steam extras from the pack (library art, Steam Deck controller layout), into this device's Steam shortcuts.
# steam-target.json (written by setup) says where: "targets", one per shortcut, each with its grid folder, app id,
# controller layout folder and which art it gets ("pack": October Journey's, for the shortcut that starts the game;
# "prism": Prism Launcher's logo, for the one that opens Prism). From before there were two shortcuts it may just have
# grid / appid / controller for one shortcut with the pack's art. First time: installed, keeping what was there as
# *.before-pack. After that a file is replaced only while it is still the version last installed here, so art or a
# layout changed in Steam is kept. Steam reads both when it starts: a change shows after Steam is next restarted.
# pack-options.json (written by setup and the uninstaller) says which of these the player wants kept up to date:
# steam_art, steam_controller, server_entry. Missing file or key: yes.
$opt = @{ steam_art = $true; steam_controller = $true; server_entry = $true }
$optPath = Join-Path $here 'pack-options.json'
if (Test-Path $optPath) { try { (Get-Content $optPath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { if ($_.Value -is [bool]) { $opt[$_.Name] = $_.Value } } } catch { } }
$target = Join-Path $here 'steam-target.json'
if (-not (Test-Path $target)) { Status 'off' 'Steam art and controller layouts' 'No Steam shortcut set up by setup' }
elseif (-not ($opt.steam_art -or $opt.steam_controller)) { Status 'off' 'Steam art and controller layouts' 'Not kept up to date (your choice at setup)' }
if ((Test-Path $target) -and ($opt.steam_art -or $opt.steam_controller)) {
    try {
        $st = Get-Content $target -Raw | ConvertFrom-Json
        $legacy = -not $st.targets
        $targets = @(if ($legacy) { @([pscustomobject]@{ grid = $st.grid; appid = $st.appid; controller = $st.controller; art = 'pack' }) } else { @($st.targets | ForEach-Object { $_ }) })
        $statePath = Join-Path $here 'steam-applied.json'
        $state = @{}
        if (Test-Path $statePath) { (Get-Content $statePath -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $state[$_.Name] = $_.Value } }
        $sha = [Security.Cryptography.SHA256]::Create()
        function Get-PackHash([byte[]]$b) { -join ($sha.ComputeHash($b) | ForEach-Object { $_.ToString('x2') }) }
        $changed = @()
        foreach ($tg in $targets) {
            $artDir = if ($tg.art -eq 'prism') { 'steam/art-prism' } elseif ($tg.art -eq 'launcher') { 'steam/art-launcher' } else { 'steam/art' }
            $items = @(
                @("$artDir/cover.png", (Join-Path $tg.grid "$($tg.appid)p.png")),
                @("$artDir/hero.png", (Join-Path $tg.grid "$($tg.appid)_hero.png")),
                @("$artDir/logo.png", (Join-Path $tg.grid "$($tg.appid)_logo.png")),
                @("$artDir/icon.png", (Join-Path $tg.grid "$($tg.appid)_icon.png")),
                @("$artDir/wide.png", (Join-Path $tg.grid "$($tg.appid).png")),
                @('steam/controller_neptune.vdf', (Join-Path $tg.controller 'controller_neptune.vdf')))
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
                # What was last installed there (recorded by destination; by pack file before there were two shortcuts).
                $last = $state[$dst]; if (-not $last -and $legacy) { $last = $state[$i[0]] }
                if ($have -eq $new) { $state[$dst] = $new; continue }
                if ($have -and $last -and $have -ne $last) { continue }   # changed in Steam: kept
                if ($have -and -not $last) { Copy-Item $dst "$dst.before-pack" -Force }
                New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
                [IO.File]::WriteAllBytes($dst, $bytes)
                $state[$dst] = $new; $changed += (Split-Path $i[0] -Leaf)
            }
            # The layout is only used once Steam's list for this controller type names it.
            $set = Join-Path (Split-Path $tg.controller) 'configset_controller_neptune.vdf'
            $key = Split-Path $tg.controller -Leaf
            $txt = if (Test-Path $set) { [IO.File]::ReadAllText($set) } else { "`"controller_config`"`n{`n}`n" }
            if ($opt.steam_controller -and $txt.IndexOf("`"$key`"", [StringComparison]::OrdinalIgnoreCase) -lt 0) {
                $end = $txt.LastIndexOf('}')
                $txt = $txt.Substring(0, $end) + "`t`"$key`"`n`t{`n`t`t`"autosave`"`t`t`"1`"`n`t}`n" + $txt.Substring($end)
                [IO.File]::WriteAllText($set, $txt); $changed += 'controller list'
            }
        }
        $state | ConvertTo-Json | Set-Content -Encoding utf8 $statePath
        if ($changed) { Note ("Steam extras updated: " + ($changed -join ', ') + " (shows after Steam restarts)"); Status 'updated' 'Steam art and controller layouts' "$($changed.Count) updated for $($targets.Count) shortcut(s); shows after Steam restarts" }
        else { Status 'ok' 'Steam art and controller layouts' "Up to date ($($targets.Count) shortcut$(if ($targets.Count -ne 1) { 's' }))" }
    } catch { Note "Steam extras skipped: $($_.Exception.Message)"; Status 'failed' 'Steam art and controller layouts' $_.Exception.Message }
}

# The pack's servers (extras/servers.json) in Minecraft's multiplayer list (servers.dat, uncompressed NBT). A missing one is
# added at the top; a wrong address is corrected while it is still the one this sync last set (or it never set one), so an
# entry edited or deleted in the game afterwards is left alone. Other entries are kept as they are.
$want = Join-Path $here 'extras/servers.json'
if (-not $opt.server_entry) { Status 'off' 'Server list' 'Not kept up to date (your choice at setup)' }
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
            Note ("server list: " + ($changes -join '; ')); Status 'updated' 'Server list' ($changes -join '; ')
        } else { Status 'ok' 'Server list' ((@($servers | ForEach-Object { $_.name }) -join ', ') + ' is listed') }
        $sState | ConvertTo-Json | Set-Content -Encoding utf8 $sStatePath
    } catch { Note "server list skipped: $($_.Exception.Message)"; Status 'failed' 'Server list' $_.Exception.Message }
}
# This script, from the pack: the next launch runs the pack's copy. (PowerShell has already read this file, so replacing
# it now is safe.)
$next = Join-Path $here 'extras/pack-sync.ps1'
$self = $MyInvocation.MyCommand.Path
if ((Test-Path $next) -and (Get-FileHash $next).Hash -ne (Get-FileHash $self).Hash) {
    try { Copy-Item $next "$self.new" -Force; Move-Item "$self.new" $self -Force; Note 'sync script updated (used from the next launch)'; Status 'updated' 'Update script' 'New version, used from the next launch' }
    catch { Note "sync script update skipped: $($_.Exception.Message)"; Status 'failed' 'Update script' $_.Exception.Message }
} elseif (Test-Path $next) { Status 'ok' 'Update script' 'Current' }
AfterPopup (ShowPopup $(if ("$code" -eq '0') { 'ok' } else { 'failed' }))
exit 0
