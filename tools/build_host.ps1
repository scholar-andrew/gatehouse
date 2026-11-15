# Builds the host helper and panel (host/src, plain Java 17) into host/oj-host.jar. It runs only on the PC that hosts
# the server; tools/install_host.ps1 puts it in place there.
#   powershell -ExecutionPolicy Bypass -File tools\build_host.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin'
$out = Join-Path $env:TEMP 'packmod-build-host'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
$files = @(Get-ChildItem (Join-Path $root 'host\src') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -d (Join-Path $out 'classes') @files
if ($LASTEXITCODE) { throw 'javac failed' }
$jar = Join-Path $root 'host\oj-host.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') --create --file $jar --main-class dev.octoberjourney.host.HostHelper -C (Join-Path $out 'classes') .
if ($LASTEXITCODE) { throw 'jar failed' }
"$jar"
