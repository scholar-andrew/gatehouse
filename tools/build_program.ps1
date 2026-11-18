# Builds Gatehouse, the one program with both halves in it: Play (the launcher, mods-src\launch-popup) and Host
# (Gatekeeper, host\src), with the look they share (mods-src\look) and where it starts (mods-src\program). Plain Java,
# built for Java 17.
#   powershell -ExecutionPolicy Bypass -File tools\build_program.ps1
# The same file is written under both of the names the halves have always had, so every device keeps updating the way
# it does: source\files\launch-popup.jar (the pack ships it as extras/launch-popup.jar) and host\oj-host.jar (published
# as gatekeeper/oj-host.jar). Then run tools/build_pack.py.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin'
$out = Join-Path $env:TEMP 'packmod-build-program'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
$classes = Join-Path $out 'classes'
# One program, one version: both halves' notes carry the same number.
$house = (Get-Content (Join-Path $root 'source\gatehouse.json') -Raw | ConvertFrom-Json).version
$keeper = (Get-Content (Join-Path $root 'source\gatekeeper.json') -Raw | ConvertFrom-Json).version
if ($house -ne $keeper) { throw "source\gatehouse.json says $house and source\gatekeeper.json says $keeper : they are one program now and carry one version" }
$files = @()
foreach ($src in 'mods-src\launch-popup\src', 'host\src', 'mods-src\look\src', 'mods-src\program\src') { $files += @(Get-ChildItem (Join-Path $root $src) -Recurse -Filter *.java | ForEach-Object FullName) }
$list = Join-Path $out 'sources.txt'
$files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Encoding ascii $list
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -d $classes "@$list"
if ($LASTEXITCODE) { throw 'javac failed' }
# One class is built for Java 22: the Windows controller reader, which uses Java's own foreign-function support (final
# in 22). The launcher loads it by name and does without it on an older Java.
$files22 = @(Get-ChildItem (Join-Path $root 'mods-src\launch-popup\src22') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 22 -cp $classes -d $classes @files22
if ($LASTEXITCODE) { throw 'javac (Java 22 part) failed' }
# The chat's video and sound player (R62), built against OpenJFX and put inside as media-player.jar: devices fetch
# OpenJFX itself the first time they play something (dev.gatehouse.look.MediaEngine). Building needs OpenJFX's Windows
# parts in tools\cache\openjfx-21.0.6 (not in the repository); they are fetched once from Maven Central and checked.
$fx = Join-Path $root 'tools\cache\openjfx-21.0.6'
$fxParts = [ordered]@{ 'javafx-base' = '618a1149089d1459cc569cc10eac05c5589a532473d09f35c1e9ea2c5b6de50e'; 'javafx-graphics' = '4b0981384551f39a0ca9679672f2abbedd9d0966763205534fca230b1cf314a5'; 'javafx-media' = '59fbb279c303eb9a4598ca88228884bbc267f287769b6d22af8e65b923af25a6' }
New-Item -ItemType Directory -Force $fx | Out-Null
$fxJars = @()
foreach ($m in $fxParts.Keys) {
    $f = Join-Path $fx "$m-21.0.6-win.jar"
    if (-not (Test-Path $f)) { Invoke-WebRequest -UseBasicParsing "https://repo1.maven.org/maven2/org/openjfx/$m/21.0.6/$m-21.0.6-win.jar" -OutFile $f }
    if ((Get-FileHash $f -Algorithm SHA256).Hash.ToLower() -ne $fxParts[$m]) { Remove-Item $f; throw "$f didn't match its checksum" }
    $fxJars += $f
}
$media = Join-Path $out 'media'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -cp ($fxJars -join ';') -d $media @(Get-ChildItem (Join-Path $root 'mods-src\media\src') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE) { throw 'javac (media player) failed' }
& (Join-Path $jdk 'jar.exe') --create --file (Join-Path $classes 'dev\gatehouse\look\media-player.jar') -C $media .
if ($LASTEXITCODE) { throw 'jar (media player) failed' }
# Each half's version and notes go inside (the Gatekeeper updates and Gatehouse updates tabs).
Copy-Item (Join-Path $root 'source\gatekeeper.json') (Join-Path $classes 'gatekeeper-notes.json')
Copy-Item (Join-Path $root 'source\gatehouse.json') (Join-Path $classes 'gatehouse-notes.json')
# (the release key's public half: updates are checked against it; see dev.gatehouse.Signed)
if (Test-Path (Join-Path $root 'source\release-key.pub.pem')) { Copy-Item (Join-Path $root 'source\release-key.pub.pem') $classes }
# The install scripts go inside too: for setting a half up from the first screen, and for Options > Uninstall.
Copy-Item (Join-Path $root 'installer\gatehouse-install.ps1'), (Join-Path $root 'installer\gatehouse-install.sh') $classes
Copy-Item (Join-Path $root 'installer\keeper\install-keeper.ps1'), (Join-Path $root 'installer\keeper\install-keeper.sh') $classes
# The web panel's page (host\web): served from inside the program.
New-Item -ItemType Directory -Force (Join-Path $classes 'web') | Out-Null
Copy-Item (Join-Path $root 'host\web\*') (Join-Path $classes 'web')
# What every pack a Gatekeeper builds carries of the program itself (PackBuilder): the sync scripts, the launch
# wrappers, the launcher's scripts, the art for the Steam shortcuts, and the Steam Deck controller layout.
$extras = Join-Path $classes 'pack-extras'
New-Item -ItemType Directory -Force (Join-Path $extras 'clients'), (Join-Path $extras 'art'), (Join-Path $extras 'art-prism'), (Join-Path $extras 'art-launcher'), (Join-Path $extras 'steam') | Out-Null
foreach ($n in 'pack-sync.ps1', 'pack-sync.sh', 'launch-wrapper.sh', 'launch-wrapper.exe', 'gatehouse-launcher.ps1', 'gatehouse-launcher.sh', 'plain-launch.ps1', 'plain-launch.sh', 'packwiz-installer-bootstrap.jar') { Copy-Item (Join-Path $root "clients\$n") (Join-Path $extras 'clients') }
foreach ($set in 'art', 'art-prism', 'art-launcher') { Copy-Item (Join-Path $root "installer\$set\*.png") (Join-Path $extras $set) }
Copy-Item (Join-Path $root 'installer\steam\controller_neptune.vdf') (Join-Path $extras 'steam')
# Pictures for the setup guides' steps, when there are any: source\wizard\gatehouse|gatekeeper\<step key>.png
foreach ($half in 'gatehouse', 'gatekeeper') {
    $art = Join-Path $root "source\wizard\$half"
    if (Test-Path $art) { New-Item -ItemType Directory -Force (Join-Path $classes "wizard\$half") | Out-Null; Copy-Item (Join-Path $art '*.png') (Join-Path $classes "wizard\$half") }
}
# (so that Java doesn't warn about the controller reader calling into Windows)
Set-Content -Path (Join-Path $out 'manifest.txt') -Value @('Enable-Native-Access: ALL-UNNAMED', 'Add-Opens: java.desktop/sun.awt.X11') -Encoding ascii
$jar = Join-Path $out 'gatehouse.jar'
& (Join-Path $jdk 'jar.exe') --create --file $jar --main-class dev.gatehouse.Main --manifest (Join-Path $out 'manifest.txt') -C $classes .
if ($LASTEXITCODE) { throw 'jar failed' }
$play = Join-Path $root 'source\files\launch-popup.jar'; $host_ = Join-Path $root 'host\oj-host.jar'
Copy-Item $jar $play -Force; Copy-Item $jar $host_ -Force
"$play"
"$host_"
