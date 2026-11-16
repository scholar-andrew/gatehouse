# Builds the launch pop-up (mods-src/launch-popup, plain Java with no Minecraft code) into source/files/launch-popup.jar.
# The pack ships it as extras/launch-popup.jar and the sync scripts run it before Minecraft starts. Built for Java 17 so
# any Java Prism hands the sync can run it.
#   powershell -ExecutionPolicy Bypass -File tools\build_popup.ps1
# Then run tools/build_pack.py.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin'
$out = Join-Path $env:TEMP 'packmod-build-launch-popup'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
$files = @(Get-ChildItem (Join-Path $root 'mods-src\launch-popup\src') -Recurse -Filter *.java | ForEach-Object FullName)
# (and the look both programs share: mods-src\look)
$files += @(Get-ChildItem (Join-Path $root 'mods-src\look\src') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -d (Join-Path $out 'classes') @files
if ($LASTEXITCODE) { throw 'javac failed' }
# One class is built for Java 22: the Windows controller reader, which uses Java's own foreign-function support (final
# in 22). The launcher loads it by name and does without it on an older Java.
$files22 = @(Get-ChildItem (Join-Path $root 'mods-src\launch-popup\src22') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 22 -cp (Join-Path $out 'classes') -d (Join-Path $out 'classes') @files22
if ($LASTEXITCODE) { throw 'javac (Java 22 part) failed' }
# Gatehouse's own version and notes go inside it (source\gatehouse.json), apart from any pack's.
Copy-Item (Join-Path $root 'source\gatehouse.json') (Join-Path $out 'classes\gatehouse-notes.json')
# (so that Java doesn't warn about that reader calling into Windows)
Set-Content -Path (Join-Path $out 'manifest.txt') -Value 'Enable-Native-Access: ALL-UNNAMED' -Encoding ascii
$jar = Join-Path $root 'source\files\launch-popup.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') --create --file $jar --main-class dev.octoberjourney.popup.LaunchPopup --manifest (Join-Path $out 'manifest.txt') -C (Join-Path $out 'classes') .
if ($LASTEXITCODE) { throw 'jar failed' }
"$jar"
