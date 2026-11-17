# Gatehouse installer and uninstaller for Windows. A plain script: read it before you run it.
#
# It installs what you choose:
#   Gatehouse    the launcher players use. Needs Prism Launcher (offered if it isn't installed) and Java 17 or newer
#                (found on the computer, or offered from Eclipse Adoptium). No game and no server pack is installed:
#                in Gatehouse you add a Gatekeeper with what its owner gave you, and install its servers from there.
#   Gatekeeper   the program for the machine that runs the Minecraft servers (with MC Server Soft). It lets the
#                launchers you allow start and stop them. Needs Java 17 or newer.
#
# What it does to this computer, and nothing else:
#   - Gatehouse: files in <Prism Launcher's folder>\gatehouse\app (the launcher, the scripts that start it, where to
#     look for a newer launcher, art for Steam, and a copy of this script), and Start menu shortcuts "Gatehouse" and
#     "Uninstall Gatehouse". The shortcuts run Windows' own powershell.exe with one of those scripts. Nothing runs in
#     the background.
#   - Gatekeeper: this downloads Gatekeeper and its own install script (install-keeper.ps1, also plain text) and runs
#     that script, which says what it does: a folder, a task that starts Gatekeeper when you sign in, a Start menu
#     shortcut.
#   - Steam, if you say yes: a shortcut for what was installed is added to your Steam library ("Gatehouse",
#     "Gatekeeper"), with its art. Steam has to be closed while that is done (it writes its own list of shortcuts
#     when it exits); this script never closes or starts Steam. Steam's list as it was is kept beside it as
#     shortcuts.vdf.bak-gatehouse.
#   - Java, only if none is found and you agree: unpacked into %LOCALAPPDATA%\Gatehouse\jre. Not added to the PATH.
#   - Prism Launcher, only if it's missing and you agree: its own installer is downloaded and run; you click through it.
# Every download is checked against a published SHA-256 before it is used. Nothing is sent anywhere.
#
# -Uninstall takes out again what this put in: the Steam shortcuts, the Start menu shortcuts and Gatehouse's files; for
# Gatekeeper it runs install-keeper.ps1 -Uninstall. Prism Launcher, anything installed in it, and Java are left alone.
# With -Purge it also removes what Gatehouse remembered (its Gatekeepers, this device's identity with them) and the
# Java fetched for it, and Gatekeeper's settings.
#
# Run it:   powershell -ExecutionPolicy Bypass -File gatehouse-install.ps1      (or double-click "Install Gatehouse.cmd")
# Options:  -Gatehouse / -Gatekeeper   choose without being asked (both may be given)
#           -Uninstall [-Purge]        remove instead of install
#           -WithPack                  (with -Uninstall -Gatehouse) this Gatehouse came with a pack rather than being
#                                      installed by itself: take out what is Gatehouse's own anyway (Gatehouse's
#                                      Options page uses this when no pack uninstaller is on record)
#           -Steam / -NoSteam          add to Steam without being asked / don't
#           -Yes                       answer yes to the questions (downloads included); Steam is then only touched
#                                      if it is already closed
#           -NoPrism / -NoJava / -NoShortcut   leave that part out
#           -Source URL                where Gatehouse is published (default: its GitHub repository)
#           -PrismRoot DIR             Prism Launcher's data folder (default: %APPDATA%\PrismLauncher)
#           -SteamConfig DIR           Steam's userdata\<account>\config folder (default: found from the registry)
#           -DryRun                    say what would be done, and do none of it (nothing is downloaded or written)
param([switch]$Gatehouse, [switch]$Gatekeeper, [switch]$Uninstall, [switch]$Purge, [switch]$WithPack, [switch]$Steam, [switch]$NoSteam, [switch]$Yes,
      [switch]$NoPrism, [switch]$NoJava, [switch]$NoShortcut, [string]$Source, [string]$PrismRoot, [string]$SteamConfig, [switch]$DryRun)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$repo = 'https://raw.githubusercontent.com/scholar-andrew/gatehouse/main'
if (-not $Source) { $Source = "$repo/dist" }
$Source = $Source.TrimEnd('/')
if (-not $PrismRoot) { $PrismRoot = Join-Path $env:APPDATA 'PrismLauncher' }
$work = Join-Path ([IO.Path]::GetTempPath()) ('gatehouse-install-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$app = Join-Path $PrismRoot 'gatehouse\app'
$programs = [Environment]::GetFolderPath('Programs')
$powershellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

function Say($text) { Write-Host $text }
function Ask($question) {
    if ($Yes) { return $true }
    $a = Read-Host "$question [Y/n]"
    return ($a -eq '' -or $a -match '^[Yy]')
}
function Sha256($path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower() }
# One download, kept only if it is what was published.
function Fetch($url, $to, $sha256) {
    if ($DryRun) { Say "  would download $url"; return $true }
    New-Item -ItemType Directory -Force (Split-Path -Parent $to) | Out-Null
    $part = "$to.part"
    (New-Object Net.WebClient).DownloadFile($url, $part)
    if ($sha256 -and (Sha256 $part) -ne $sha256.ToLower()) { Remove-Item -LiteralPath $part -Force; throw "What came from $url isn't what was published (its checksum differs). Nothing was installed from it." }
    Move-Item -LiteralPath $part -Destination $to -Force
    return $true
}
function Text($url) { (New-Object Net.WebClient).DownloadString($url) }
# A Java that is 17 or newer: Gatehouse's own, Prism Launcher's, the usual Program Files folders, the PATH.
function Find-Java {
    $homes = @()
    foreach ($root in @((Join-Path $env:LOCALAPPDATA 'Gatehouse\jre'), (Join-Path $PrismRoot 'java'))) {
        if (Test-Path $root) { $homes += @(Get-ChildItem $root -Recurse -Filter javaw.exe -ErrorAction SilentlyContinue | ForEach-Object { Split-Path -Parent (Split-Path -Parent $_.FullName) }) }
    }
    foreach ($pf in @($env:ProgramW6432, $env:ProgramFiles) | Where-Object { $_ } | Select-Object -Unique) {
        foreach ($vendor in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu') { $v = Join-Path $pf $vendor; if (Test-Path $v) { $homes += @(Get-ChildItem $v -Directory | ForEach-Object FullName) } }
    }
    if ($env:JAVA_HOME) { $homes += $env:JAVA_HOME }
    foreach ($p in ($env:PATH -split ';')) { if ($p -and (Test-Path (Join-Path $p 'javaw.exe'))) { $homes += (Split-Path -Parent $p.TrimEnd('\')) } }
    $best = $null; $bestV = 0
    foreach ($h in $homes) {
        $exe = Join-Path $h 'bin\javaw.exe'; $rel = Join-Path $h 'release'
        if ((Test-Path $exe) -and (Test-Path $rel) -and ((Get-Content $rel -Raw) -match 'JAVA_VERSION="(\d+)')) { $v = [int]$Matches[1]; if ($v -ge 17 -and $v -gt $bestV) { $best = $exe; $bestV = $v } }
    }
    return $best
}
# Java from Eclipse Adoptium (the Temurin runtime, version 21), when none was found and the person agrees.
function Get-Java {
    $java = Find-Java
    if ($java) { Say "Java: found ($java)."; return $java }
    if ($NoJava) { Say 'Java: none found that is 17 or newer, and -NoJava was given. Install one (adoptium.net) and run this again.'; return $null }
    $api = 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
    if ($DryRun) { Say "Java: none found. Would ask Adoptium ($api) for its Java 21 runtime and unpack it into $env:LOCALAPPDATA\Gatehouse\jre."; return 'javaw.exe' }
    $pkg = (Text $api | ConvertFrom-Json)[0].binary.package
    $mb = [Math]::Round($pkg.size / 1MB)
    if (-not (Ask "Java 17 or newer wasn't found. Download the Java 21 runtime from Eclipse Adoptium ($($pkg.name), $mb MB) for Gatehouse's own use?")) { Say 'Java: not installed. Install one (adoptium.net) and run this again.'; return $null }
    $zip = Join-Path $work $pkg.name
    Fetch $pkg.link $zip $pkg.checksum | Out-Null
    $dest = Join-Path $env:LOCALAPPDATA 'Gatehouse\jre'
    New-Item -ItemType Directory -Force $dest | Out-Null
    Expand-Archive -LiteralPath $zip -DestinationPath $dest -Force
    $java = Find-Java
    if ($java) { Say "Java: installed for Gatehouse ($java)." } else { Say "Java: unpacked into $dest, but no javaw.exe was found there." }
    return $java
}
function Find-Prism {
    foreach ($c in @((Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe'), (Join-Path $env:ProgramFiles 'PrismLauncher\prismlauncher.exe'))) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}
function Link($path, $target, $arguments, $workDir, $description) {
    $s = (New-Object -ComObject WScript.Shell).CreateShortcut($path)
    $s.TargetPath = $target; $s.Arguments = $arguments; $s.WorkingDirectory = $workDir; $s.Description = $description; $s.Save()
}

# ---- Steam. The launcher itself knows Steam's list of shortcuts (a binary file): this script only asks it, with
# "java -jar launch-popup.jar --steam from <a small file saying what to do>". Steam is never closed or started here.
function Steam-Here { if ($SteamConfig) { return $true }; try { return [bool](Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction Stop).SteamPath } catch { return $false } }
function Steam-Running { if ($SteamConfig) { return $false }; return [bool](Get-Process steam -ErrorAction SilentlyContinue) }
# True once Steam is closed; false when the person would rather leave Steam out of it.
function Steam-Closed($what) {
    while (Steam-Running) {
        if ($Yes) { Say "Steam: it is running, so $what was left as it is. Close Steam and run this again to do that part."; return $false }
        $a = Read-Host 'Steam is running, and has to be closed for this (Steam menu, Exit). Close it and press Enter, or type s to skip the Steam part'
        if ($a -match '^[Ss]') { return $false }
    }
    return $true
}
# The launcher's program file: Gatehouse's own copy, else fetched for the moment (and checked).
function Launcher-Jar {
    $own = Join-Path $app 'extras\launch-popup.jar'
    if (Test-Path $own) { return $own }
    $sizes = Text "$Source/main/update-sizes.json" | ConvertFrom-Json
    $tmp = Join-Path $work 'launch-popup.jar'
    Fetch "$Source/main/extras/launch-popup.jar" $tmp $sizes.files.'extras/launch-popup.jar'.hash | Out-Null
    return $tmp
}
function Steam-Ask($java, [string[]]$lines, $art) {
    New-Item -ItemType Directory -Force $work | Out-Null
    $f = Join-Path $work 'steam.txt'
    [IO.File]::WriteAllLines($f, $lines, (New-Object Text.UTF8Encoding($false)))
    $console = Join-Path (Split-Path -Parent $java) 'java.exe'; if (-not (Test-Path $console)) { $console = $java }
    $a = @('-jar', (Launcher-Jar), '--steam', 'from', $f)
    if ($SteamConfig) { $a += @('--config', $SteamConfig) }
    if ($art -and (Test-Path $art)) { $a += @('--art', $art) }
    $said = & $console @a 2>$null
    Say "Steam: $($said -join ' ')"
    return ($LASTEXITCODE -eq 0)
}
function Steam-Add($java, $name, $program, $startDir, $options, $art) {
    if ($NoSteam) { return }
    if (-not (Steam-Here)) { Say "Steam: not installed here, so no Steam shortcut for $name."; return }
    if (-not $java) { Say "Steam: no Java to do it with, so no Steam shortcut for $name."; return }
    if ($DryRun) { Say "Steam: would offer to add `"$name`" to your Steam library (Steam has to be closed for that)."; return }
    if (-not $Steam -and -not (Ask "Add `"$name`" to your Steam library?")) { return }
    if (-not (Steam-Closed "adding `"$name`"")) { return }
    Steam-Ask $java @('add', $name, $program, $startDir, $options) $art | Out-Null
}
function Steam-Remove($java, $name) {
    if ($NoSteam -or -not (Steam-Here)) { return }
    if (-not $java) { Say "Steam: no Java to do it with: if `"$name`" is in your Steam library, remove it there (right-click, Manage, Remove non-Steam game)."; return }
    if ($DryRun) { Say "Steam: would take `"$name`" out of your Steam library if it is there (Steam has to be closed for that)."; return }
    if (-not (Steam-Closed "`"$name`" in your Steam library")) { Say "Steam: if `"$name`" is in your library, remove it there (right-click, Manage, Remove non-Steam game)."; return }
    Steam-Ask $java @('remove', $name) $null | Out-Null
}

function Install-Gatehouse {
    Say ''
    Say 'Gatehouse (the launcher)'
    # ---- Prism Launcher
    $prism = Find-Prism
    if ($prism) { Say "Prism Launcher: found ($prism)." }
    elseif ($NoPrism) { Say 'Prism Launcher: not found, and -NoPrism was given. Gatehouse needs it to start the game.' }
    else {
        $cfg = $null
        if (-not $DryRun) { $cfg = (Text "$repo/installer/config.json" | ConvertFrom-Json).prism_windows }
        if ($DryRun) { Say "Prism Launcher: not found. Would offer its own installer, from the address in $repo/installer/config.json." }
        elseif (Ask "Prism Launcher isn't installed. Download its installer (version $($cfg.version), from Prism Launcher's GitHub releases) and run it?") {
            $setup = Join-Path $work 'PrismLauncher-Setup.exe'
            Fetch $cfg.url $setup $cfg.sha256 | Out-Null
            Say 'Prism Launcher: running its installer. Click through it, then come back here.'
            Start-Process -Wait $setup
            $prism = Find-Prism
            if ($prism) { Say "Prism Launcher: installed ($prism)." } else { Say "Prism Launcher: its installer ended, but Prism wasn't found in the usual place. Gatehouse is set up all the same; run this again once Prism is installed." }
        } else { Say 'Prism Launcher: not installed. Gatehouse is set up all the same, but needs Prism to start the game.' }
    }
    # ---- the launcher, its scripts and its art for Steam: what the source publishes, each file checked
    $names = 'extras/launch-popup.jar', 'extras/gatehouse-launcher.ps1', 'extras/gatehouse-launcher.sh', 'extras/plain-launch.ps1', 'extras/plain-launch.sh', 'extras/launch-wrapper.exe', 'extras/launch-wrapper.sh',
             'steam/art-launcher/cover.png', 'steam/art-launcher/hero.png', 'steam/art-launcher/logo.png', 'steam/art-launcher/icon.png', 'steam/art-launcher/wide.png'
    if ($DryRun) { Say "Launcher: would fetch $($names.Count) files from $Source/main into $app, each checked against $Source/main/update-sizes.json." }
    else {
        $sizes = Text "$Source/main/update-sizes.json" | ConvertFrom-Json
        foreach ($n in $names) {
            $entry = $sizes.files.$n
            if (-not $entry) { if ($n -eq 'extras/launch-popup.jar') { throw "The source ($Source) doesn't list the launcher." } else { continue } }
            Fetch "$Source/main/$n" (Join-Path $app ($n -replace '/', '\')) $entry.hash | Out-Null
        }
        # (where the launcher looks for a newer copy of itself: this source, then the repository)
        $places = @($Source); if ($Source -ne "$repo/dist") { $places += "$repo/dist" }
        Set-Content -Path (Join-Path $app 'pack-sources.txt') -Value (@('# Where Gatehouse looks for a newer launcher, in order. (Written by gatehouse-install.ps1.)') + $places) -Encoding ascii
        New-Item -ItemType Directory -Force (Join-Path $PrismRoot 'instances') | Out-Null
        # (a copy of this script, for removing Gatehouse later)
        if ($PSCommandPath -and (Test-Path $PSCommandPath) -and ($PSCommandPath -ne (Join-Path $app 'gatehouse-install.ps1'))) { Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $app 'gatehouse-install.ps1') -Force }
        Say "Launcher: Gatehouse $($sizes.gatehouse) is in $app."
    }
    # ---- Java
    $java = Get-Java
    # ---- Start menu shortcuts: Windows' own PowerShell, running the launcher's script (and this one, to uninstall)
    $script = Join-Path $app 'extras\gatehouse-launcher.ps1'
    $options = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script + '"' + $(if ($prism) { ' -Prism "' + $prism + '"' } else { '' })
    if ($NoShortcut) { Say 'Shortcuts: left out (-NoShortcut).' }
    elseif ($DryRun) { Say "Shortcuts: would make `"Gatehouse`" and `"Uninstall Gatehouse`" in the Start menu ($programs), both running powershell.exe with a script in $app." }
    else {
        # (the Start menu opens the program's first screen, Play or Host; Steam, below, goes straight into the launcher)
        Link (Join-Path $programs 'Gatehouse.lnk') $powershellExe ($options + ' -Choose') (Split-Path -Parent $script) 'Gatehouse: play, or host'
        Link (Join-Path $programs 'Uninstall Gatehouse.lnk') $powershellExe ('-NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $app 'gatehouse-install.ps1') + '" -Uninstall -Gatehouse') $app 'Removes the Gatehouse launcher'
        Say 'Shortcuts: "Gatehouse" and "Uninstall Gatehouse" are in the Start menu.'
    }
    # ---- Steam
    Steam-Add $java 'Gatehouse' $powershellExe ((Split-Path -Parent $powershellExe) + '\') $options (Join-Path $app 'steam\art-launcher')
    Say ''
    if ($DryRun) { Say 'Gatehouse: nothing was changed (-DryRun).' }
    elseif (-not $java) { Say 'Gatehouse is in place, but has no Java to run on yet (see above).' }
    else { Say 'Gatehouse is installed. Open it from the Start menu, press Add Gatekeeper, and paste the address, link or file its owner gave you.' }
}

function Uninstall-Gatehouse {
    Say ''
    Say 'Removing Gatehouse (the launcher)'
    $keepers = Join-Path $PrismRoot 'gatehouse'
    $runs = @(Get-ChildItem $PrismRoot -Filter 'gatehouse-launcher-run*.jar' -ErrorAction SilentlyContinue)
    $links = @((Join-Path $programs 'Gatehouse.lnk'), (Join-Path $programs 'Uninstall Gatehouse.lnk')) | Where-Object { Test-Path $_ }
    if (-not (Test-Path $app) -and -not $links -and -not $WithPack) { Say "Gatehouse isn't installed by itself here (nothing in $app). A Gatehouse that came with a pack goes with that pack's uninstaller."; return }
    if (-not $DryRun -and -not (Ask "Remove Gatehouse from this computer?$(if ($Purge) { ' (And what it remembered: its Gatekeepers and this device''s identity with them.)' })")) { Say 'Nothing was changed.'; return }
    Steam-Remove (Find-Java) 'Gatehouse'
    if ($DryRun) {
        Say "Would remove: $app; $($runs.Count) working cop$(if ($runs.Count -eq 1) { 'y' } else { 'ies' }) of the launcher in $PrismRoot; the Start menu shortcuts ($($links.Count))."
        if ($Purge) { Say "Would also remove: $keepers (its Gatekeepers and this device's identity with them) and $env:LOCALAPPDATA\Gatehouse (Java fetched for it)." }
        Say 'Gatehouse: nothing was changed (-DryRun).'
        return
    }
    foreach ($l in $links) { Remove-Item -LiteralPath $l -Force }
    foreach ($r in $runs) { Remove-Item -LiteralPath $r.FullName -Force -ErrorAction SilentlyContinue }
    if (Test-Path $app) { Remove-Item -LiteralPath $app -Recurse -Force }
    if ($Purge) {
        if (Test-Path $keepers) { Remove-Item -LiteralPath $keepers -Recurse -Force }
        $own = Join-Path $env:LOCALAPPDATA 'Gatehouse'; if (Test-Path $own) { Remove-Item -LiteralPath $own -Recurse -Force }
        Say 'Gatehouse is removed, with what it remembered and the Java fetched for it.'
    } else {
        if ((Test-Path $keepers) -and -not (Get-ChildItem $keepers -Force)) { Remove-Item -LiteralPath $keepers -Force }
        Say "Gatehouse is removed.$(if (Test-Path $keepers) { " What it remembered (its Gatekeepers, this device's identity with them) is still in $keepers; -Purge removes that too." })"
    }
    Say 'Left alone: Prism Launcher and everything installed in it.'
    if ($WithPack) {
        Say 'The servers installed through Gatehouse are still in Prism Launcher, each with its own launcher page: to remove one, open Prism Launcher, right-click it and choose Delete.'
        Say 'If "Gatehouse Minecraft Launcher" is in your Steam library, remove it there (right-click, Manage, Remove non-Steam game).'
    }
}

# Where Gatekeeper is installed, as its own Start menu shortcut says: {javaw.exe, its arguments, its folder}.
function Keeper-Shortcut {
    $l = Join-Path $programs 'Gatekeeper.lnk'
    if (-not (Test-Path $l)) { return $null }
    $s = (New-Object -ComObject WScript.Shell).CreateShortcut($l)
    return @($s.TargetPath, $s.Arguments, $s.WorkingDirectory)
}

function Install-Gatekeeper {
    Say ''
    Say 'Gatekeeper (for the machine that runs the servers)'
    if ($DryRun) {
        Say "  would read $Source/gatekeeper/gatekeeper.json, fetch $Source/gatekeeper/oj-host.jar (checked against it) and $repo/installer/keeper/install-keeper.ps1,"
        Say '  and run install-keeper.ps1, which installs Gatekeeper and says what it does.'
        Steam-Add 'javaw.exe' 'Gatekeeper' '' '' '' $null
        Say 'Gatekeeper: nothing was changed (-DryRun).'
        return
    }
    $m = Text "$Source/gatekeeper/gatekeeper.json" | ConvertFrom-Json
    $dir = Join-Path $work 'keeper'
    Fetch "$Source/gatekeeper/oj-host.jar" (Join-Path $dir 'oj-host.jar') $m.sha256 | Out-Null
    Fetch "$repo/installer/keeper/install-keeper.ps1" (Join-Path $dir 'install-keeper.ps1') $null | Out-Null
    Say "Gatekeeper $($m.version): fetched and checked."
    $java = Get-Java
    if (-not $java) { Say 'Gatekeeper: not installed, because there is no Java for it to run on (see above).'; return }
    $keeperArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $dir 'install-keeper.ps1'), '-Java', $java)
    if ($Yes) { $keeperArgs += '-Yes' }
    Say 'Gatekeeper: running its install script (install-keeper.ps1).'
    & $powershellExe @keeperArgs
    # ---- Steam: Gatekeeper's window, as its Start menu shortcut starts it
    $k = Keeper-Shortcut
    if ($k) { Steam-Add $java 'Gatekeeper' $k[0] ($k[2].TrimEnd('\') + '\') $k[1] (Join-Path $app 'steam\art-launcher') }
}

function Uninstall-Gatekeeper {
    Say ''
    Say 'Removing Gatekeeper'
    if ($DryRun) {
        Steam-Remove 'javaw.exe' 'Gatekeeper'
        Say "Would fetch $repo/installer/keeper/install-keeper.ps1 and run it with -Uninstall$(if ($Purge) { ' -Purge' }): it stops Gatekeeper, removes its task, its Start menu shortcut and its program$(if ($Purge) { ', and its settings' })."
        Say 'Gatekeeper: nothing was changed (-DryRun).'
        return
    }
    Steam-Remove (Find-Java) 'Gatekeeper'
    $dir = Join-Path $work 'keeper'
    Fetch "$repo/installer/keeper/install-keeper.ps1" (Join-Path $dir 'install-keeper.ps1') $null | Out-Null
    $keeperArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $dir 'install-keeper.ps1'), '-Uninstall')
    if ($Purge) { $keeperArgs += '-Purge' }
    if ($Yes) { $keeperArgs += '-Yes' }
    & $powershellExe @keeperArgs
}

try {
    Say "Gatehouse $(if ($Uninstall) { 'uninstaller' } else { 'installer' })"
    if (-not $Gatehouse -and -not $Gatekeeper) {
        Say ''
        Say '  1  Gatehouse   - the launcher, for playing on servers a Gatekeeper looks after'
        Say '  2  Gatekeeper  - for the machine that runs the servers (with MC Server Soft)'
        Say "  3  Both$(if (-not $Uninstall) { '        - this machine runs the servers and is played on too' })"
        $pick = Read-Host "Which do you want to $(if ($Uninstall) { 'remove' } else { 'install' })? [1]"
        if ($pick -eq '2') { $Gatekeeper = $true } elseif ($pick -eq '3') { $Gatehouse = $true; $Gatekeeper = $true } else { $Gatehouse = $true }
    }
    if ($Uninstall) {
        if ($Gatekeeper) { Uninstall-Gatekeeper }
        if ($Gatehouse) { Uninstall-Gatehouse }
    } else {
        if ($Gatehouse) { Install-Gatehouse }
        if ($Gatekeeper) { Install-Gatekeeper }
    }
} catch {
    Say ''
    Say "Stopped: $($_.Exception.Message)"
    exit 1
} finally {
    if (Test-Path $work) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
}
