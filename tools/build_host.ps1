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
# (and the look both programs share: mods-src\look)
$files += @(Get-ChildItem (Join-Path $root 'mods-src\look\src') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 17 -d (Join-Path $out 'classes') @files
if ($LASTEXITCODE) { throw 'javac failed' }
# Gatekeeper's own version and notes go inside it (source\gatekeeper.json), and a copy of Gatehouse's (source\gatehouse.json)
# for when the newest can't be fetched.
Copy-Item (Join-Path $root 'source\gatekeeper.json') (Join-Path $out 'classes\gatekeeper-notes.json')
Copy-Item (Join-Path $root 'source\gatehouse.json') (Join-Path $out 'classes\gatehouse-notes.json')
# Gatekeeper carries its own install script, for Options > Uninstall (which runs it with -Uninstall).
Copy-Item (Join-Path $root 'installer\keeper\install-keeper.ps1') (Join-Path $out 'classes\install-keeper.ps1')
Copy-Item (Join-Path $root 'installer\keeper\install-keeper.sh') (Join-Path $out 'classes\install-keeper.sh')   # (Linux and SteamOS)
# Pictures for the setup guide's steps, when there are any: source\wizard\gatekeeper\<step key>.png
$art = Join-Path $root 'source\wizard\gatekeeper'
if (Test-Path $art) { New-Item -ItemType Directory -Force (Join-Path $out 'classes\wizard') | Out-Null; Copy-Item (Join-Path $art '*.png') (Join-Path $out 'classes\wizard') }
$jar = Join-Path $root 'host\oj-host.jar'
Remove-Item $jar -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') --create --file $jar --main-class dev.octoberjourney.host.HostHelper -C (Join-Path $out 'classes') .
if ($LASTEXITCODE) { throw 'jar failed' }
"$jar"
