# Gatehouse installer for Windows. A plain script: read it before you run it.
#
# It installs what you choose:
#   Gatehouse    the launcher players use. Needs Prism Launcher (offered if it isn't installed) and Java 17 or newer
#                (found on the computer, or offered from Eclipse Adoptium). No game and no server pack is installed:
#                in Gatehouse you add a Gatekeeper with what its owner gave you, and install its servers from there.
#   Gatekeeper   the program for the machine that runs the Minecraft servers (with MC Server Soft). It lets the
#                launchers you allow start and stop them. Needs Java 17 or newer.
#
# What it does to this computer, and nothing else:
#   - Gatehouse: files in <Prism Launcher's folder>\gatehouse\app (the launcher, the scripts that start it, and where
#     to look for a newer launcher), and a Start menu shortcut "Gatehouse". The shortcut runs Windows' own
#     powershell.exe with one of those scripts. Nothing runs in the background.
#   - Gatekeeper: this downloads Gatekeeper and its own install script (install-keeper.ps1, also plain text) and runs
#     that script, which says what it does: a folder, a task that starts Gatekeeper when you sign in, a Start menu
#     shortcut.
#   - Java, only if none is found and you agree: unpacked into %LOCALAPPDATA%\Gatehouse\jre. Not added to the PATH.
#   - Prism Launcher, only if it's missing and you agree: its own installer is downloaded and run; you click through it.
# Every download is checked against a published SHA-256 before it is used. Nothing is sent anywhere.
#
# Run it:   powershell -ExecutionPolicy Bypass -File gatehouse-install.ps1      (or double-click "Install Gatehouse.cmd")
# Options:  -Gatehouse / -Gatekeeper   choose without being asked (both may be given)
#           -Yes                       answer yes to the questions (downloads included)
#           -NoPrism / -NoJava / -NoShortcut   leave that part out
#           -Source URL                where Gatehouse is published (default: its GitHub repository)
#           -PrismRoot DIR             Prism Launcher's data folder (default: %APPDATA%\PrismLauncher)
#           -DryRun                    say what would be done, and do none of it (nothing is downloaded or written)
param([switch]$Gatehouse, [switch]$Gatekeeper, [switch]$Yes, [switch]$NoPrism, [switch]$NoJava, [switch]$NoShortcut,
      [string]$Source, [string]$PrismRoot, [switch]$DryRun)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$repo = 'https://raw.githubusercontent.com/scholar-andrew/gatehouse/main'
if (-not $Source) { $Source = "$repo/dist" }
$Source = $Source.TrimEnd('/')
if (-not $PrismRoot) { $PrismRoot = Join-Path $env:APPDATA 'PrismLauncher' }
$work = Join-Path ([IO.Path]::GetTempPath()) ('gatehouse-install-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))

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
    # ---- the launcher and its scripts: what the source publishes, each file checked
    $app = Join-Path $PrismRoot 'gatehouse\app'
    $names = 'launch-popup.jar', 'gatehouse-launcher.ps1', 'gatehouse-launcher.sh', 'plain-launch.ps1', 'plain-launch.sh', 'launch-wrapper.exe', 'launch-wrapper.sh'
    if ($DryRun) { Say "Launcher: would fetch $($names.Count) files from $Source/main/extras into $app\extras, each checked against $Source/main/update-sizes.json." }
    else {
        $sizes = Text "$Source/main/update-sizes.json" | ConvertFrom-Json
        foreach ($n in $names) {
            $entry = $sizes.files."extras/$n"
            if (-not $entry) { if ($n -eq 'launch-popup.jar') { throw "The source ($Source) doesn't list the launcher." } else { continue } }
            Fetch "$Source/main/extras/$n" (Join-Path $app "extras\$n") $entry.hash | Out-Null
        }
        # (where the launcher looks for a newer copy of itself: this source, then the repository)
        $places = @($Source); if ($Source -ne "$repo/dist") { $places += "$repo/dist" }
        Set-Content -Path (Join-Path $app 'pack-sources.txt') -Value (@('# Where Gatehouse looks for a newer launcher, in order. (Written by gatehouse-install.ps1.)') + $places) -Encoding ascii
        New-Item -ItemType Directory -Force (Join-Path $PrismRoot 'instances') | Out-Null
        Say "Launcher: Gatehouse $($sizes.gatehouse) is in $app."
    }
    # ---- Java
    $java = Get-Java
    # ---- a Start menu shortcut: Windows' own PowerShell, running the launcher's script
    $script = Join-Path $app 'extras\gatehouse-launcher.ps1'
    $link = Join-Path ([Environment]::GetFolderPath('Programs')) 'Gatehouse.lnk'
    if ($NoShortcut) { Say 'Shortcut: left out (-NoShortcut).' }
    elseif ($DryRun) { Say "Shortcut: would make `"$link`", which runs powershell.exe with $script." }
    else {
        $s = (New-Object -ComObject WScript.Shell).CreateShortcut($link)
        $s.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $s.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script + '"' + $(if ($prism) { ' -Prism "' + $prism + '"' } else { '' })
        $s.WorkingDirectory = Split-Path -Parent $script
        $s.Description = 'Gatehouse: your Minecraft servers and their Gatekeepers'
        $s.Save()
        Say "Shortcut: `"Gatehouse`" is in the Start menu."
    }
    Say ''
    if ($DryRun) { Say 'Gatehouse: nothing was changed (-DryRun).' }
    elseif (-not $java) { Say 'Gatehouse is in place, but has no Java to run on yet (see above).' }
    else { Say 'Gatehouse is installed. Open it from the Start menu, press Add Gatekeeper, and paste the address, link or file its owner gave you.' }
}

function Install-Gatekeeper {
    Say ''
    Say 'Gatekeeper (for the machine that runs the servers)'
    if ($DryRun) {
        Say "  would read $Source/gatekeeper/gatekeeper.json, fetch $Source/gatekeeper/oj-host.jar (checked against it) and $repo/installer/keeper/install-keeper.ps1,"
        Say '  and run install-keeper.ps1, which installs Gatekeeper and says what it does.'
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
    & powershell.exe @keeperArgs
}

try {
    Say 'Gatehouse installer'
    if (-not $Gatehouse -and -not $Gatekeeper) {
        Say ''
        Say '  1  Gatehouse   - the launcher, for playing on servers a Gatekeeper looks after'
        Say '  2  Gatekeeper  - for the machine that runs the servers (with MC Server Soft)'
        Say '  3  Both        - this machine runs the servers and is played on too'
        $pick = Read-Host 'Which do you want to install? [1]'
        if ($pick -eq '2') { $Gatekeeper = $true } elseif ($pick -eq '3') { $Gatehouse = $true; $Gatekeeper = $true } else { $Gatehouse = $true }
    }
    if ($Gatehouse) { Install-Gatehouse }
    if ($Gatekeeper) { Install-Gatekeeper }
} catch {
    Say ''
    Say "Stopped: $($_.Exception.Message)"
    exit 1
} finally {
    if (Test-Path $work) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
}
