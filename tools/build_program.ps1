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
# Each half's version and notes go inside (the Gatekeeper updates and Gatehouse updates tabs).
Copy-Item (Join-Path $root 'source\gatekeeper.json') (Join-Path $classes 'gatekeeper-notes.json')
Copy-Item (Join-Path $root 'source\gatehouse.json') (Join-Path $classes 'gatehouse-notes.json')
# The install scripts go inside too: for setting a half up from the first screen, and for Options > Uninstall.
Copy-Item (Join-Path $root 'installer\gatehouse-install.ps1'), (Join-Path $root 'installer\gatehouse-install.sh') $classes
Copy-Item (Join-Path $root 'installer\keeper\install-keeper.ps1'), (Join-Path $root 'installer\keeper\install-keeper.sh') $classes
# The web panel's page (host\web): served from inside the program.
New-Item -ItemType Directory -Force (Join-Path $classes 'web') | Out-Null
Copy-Item (Join-Path $root 'host\web\*') (Join-Path $classes 'web')
# Pictures for the setup guides' steps, when there are any: source\wizard\gatehouse|gatekeeper\<step key>.png
foreach ($half in 'gatehouse', 'gatekeeper') {
    $art = Join-Path $root "source\wizard\$half"
    if (Test-Path $art) { New-Item -ItemType Directory -Force (Join-Path $classes "wizard\$half") | Out-Null; Copy-Item (Join-Path $art '*.png') (Join-Path $classes "wizard\$half") }
}
# (so that Java doesn't warn about the controller reader calling into Windows)
Set-Content -Path (Join-Path $out 'manifest.txt') -Value 'Enable-Native-Access: ALL-UNNAMED' -Encoding ascii
$jar = Join-Path $out 'gatehouse.jar'
& (Join-Path $jdk 'jar.exe') --create --file $jar --main-class dev.gatehouse.Main --manifest (Join-Path $out 'manifest.txt') -C $classes .
if ($LASTEXITCODE) { throw 'jar failed' }
$play = Join-Path $root 'source\files\launch-popup.jar'; $host_ = Join-Path $root 'host\oj-host.jar'
Copy-Item $jar $play -Force; Copy-Item $jar $host_ -Force
"$play"
"$host_"
