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
$fxParts = [ordered]@{ 'javafx-base' = '618a1149089d1459cc569cc10eac05c5589a532473d09f35c1e9ea2c5b6de50e'; 'javafx-graphics' = '4b0981384551f39a0ca9679672f2abbedd9d0966763205534fca230b1cf314a5'; 'javafx-media' = '59fbb279c303eb9a4598ca88228884bbc267f287769b6d22af8e65b923af25a6'; 'javafx-swing' = '51ca5c40ef4189f3aebdee2a6ebd3b9276bfa496cf2bbe2aeabffc6eb8ca8b1d' }
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
# The chat's YouTube player (R85), built against OpenJFX 23.0.2's web view and put inside as web-player.jar: devices
# fetch the web view the first time they play a YouTube video here. Building needs OpenJFX 23.0.2's Windows parts in
# tools\cache\openjfx-23.0.2 (not in the repository); they are fetched once from Maven Central and checked.
$fx23 = Join-Path $root 'tools\cache\openjfx-23.0.2'
$fx23Parts = [ordered]@{ 'javafx-base' = '72f8958b4a552fca14ab3628c672f399aa68bbe6b4cd45107fe174b4670c7cb6'; 'javafx-graphics' = '6b5b3041f2735508a8aad23c5795e745d5bf78a0a6aa920bae43ab41ec22d8c2'; 'javafx-controls' = '874daf48b0ec01e4eb0caf24cdb85b32d951999837f8e1c285f6d1e1e34870ba'; 'javafx-web' = 'a18008ae0d3df1b8046a99d44f761dd01cce279ae1539a7e4685263aec2ea5fc'; 'javafx-swing' = '875d14a5c2cf8ce04e5c48c39b5c9a3ff1d794820b8a473ec010af1c631afcb3' }
New-Item -ItemType Directory -Force $fx23 | Out-Null
$fx23Jars = @()
foreach ($m in $fx23Parts.Keys) {
    $f = Join-Path $fx23 "$m-23.0.2-win.jar"
    if (-not (Test-Path $f)) { Invoke-WebRequest -UseBasicParsing "https://repo1.maven.org/maven2/org/openjfx/$m/23.0.2/$m-23.0.2-win.jar" -OutFile $f }
    if ((Get-FileHash $f -Algorithm SHA256).Hash.ToLower() -ne $fx23Parts[$m]) { Remove-Item $f; throw "$f didn't match its checksum" }
    $fx23Jars += $f
}
$web = Join-Path $out 'webplayer'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 21 -cp ($fx23Jars -join ';') -d $web @(Get-ChildItem (Join-Path $root 'mods-src\webplayer\src') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE) { throw 'javac (web player) failed' }
& (Join-Path $jdk 'jar.exe') --create --file (Join-Path $classes 'dev\gatehouse\look\web-player.jar') -C $web .
if ($LASTEXITCODE) { throw 'jar (web player) failed' }
# The built-in AI's bridge (R66), built against llama.cpp's Java binding and put inside as ai-engine.jar: a Gatekeeper
# fetches the binding and a model only when its owner opts in (dev.octoberjourney.host.BuiltinAi). Building needs the
# binding in tools\cache\llama-4.2.0 (not in the repository); it is fetched once from Maven Central and checked.
$llama = Join-Path $root 'tools\cache\llama-4.2.0\llama-4.2.0.jar'
New-Item -ItemType Directory -Force (Split-Path $llama) | Out-Null
if (-not (Test-Path $llama)) { Invoke-WebRequest -UseBasicParsing 'https://repo1.maven.org/maven2/de/kherud/llama/4.2.0/llama-4.2.0.jar' -OutFile $llama }
if ((Get-FileHash $llama -Algorithm SHA256).Hash.ToLower() -ne 'f8ed8a8420f0648dac695514d03bf627e8c5c8e31edcbbfd0ecee31a71dec0a9') { Remove-Item $llama; throw "$llama didn't match its checksum" }
$aiOut = Join-Path $out 'ai'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -cp $llama -d $aiOut @(Get-ChildItem (Join-Path $root 'mods-src\ai\src') -Recurse -Filter *.java | ForEach-Object FullName)
if ($LASTEXITCODE) { throw 'javac (built-in AI) failed' }
& (Join-Path $jdk 'jar.exe') --create --file (Join-Path $classes 'dev\octoberjourney\host\ai-engine.jar') -C $aiOut .
if ($LASTEXITCODE) { throw 'jar (built-in AI) failed' }
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
