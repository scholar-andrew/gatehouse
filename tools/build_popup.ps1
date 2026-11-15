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
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -d (Join-Path $out 'classes') @files
if ($LASTEXITCODE) { throw 'javac failed' }
$jar = Join-Path $root 'source\files\launch-popup.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') --create --file $jar --main-class dev.octoberjourney.popup.LaunchPopup -C (Join-Path $out 'classes') .
if ($LASTEXITCODE) { throw 'jar failed' }
"$jar"
