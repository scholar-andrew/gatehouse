# Builds mods-src/chatlift into source/files/mods/chatlift-<version>.jar with plain javac (Minecraft 26.x is not
# obfuscated, so no Loom or refmap is needed). Compiles against this PC's Prism libraries and the instance's Controlify.
# Afterwards: update the chatlift entry (filename, sha512) in source/mods.json and run tools/build_pack.py.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'mods-src\chatlift\src\main'
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin'
$prism = Join-Path $env:APPDATA 'PrismLauncher'
$version = (Get-Content (Join-Path $src 'resources\fabric.mod.json') -Raw | ConvertFrom-Json).version
$cp = @(Get-ChildItem (Join-Path $prism 'libraries') -Recurse -Filter *.jar | Where-Object { $_.Name -notmatch 'natives' } | ForEach-Object FullName)
$cp += (Get-ChildItem (Join-Path $prism 'instances\october-journey\minecraft\mods') -Filter 'controlify-*.jar' | Select-Object -First 1).FullName
$out = Join-Path $env:TEMP 'chatlift-build'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
$argFile = Join-Path $out 'javac.args'
$lines = @('-encoding', 'UTF-8', '--release', '25', '-proc:none', '-d', "`"$($out -replace '\\','/')/classes`"", '-cp', "`"$(($cp -join ';') -replace '\\','/')`"")
$lines += Get-ChildItem (Join-Path $src 'java') -Recurse -Filter *.java | ForEach-Object { "`"$($_.FullName -replace '\\','/')`"" }
[IO.File]::WriteAllLines($argFile, $lines)
& (Join-Path $jdk 'javac.exe') "@$argFile"
if ($LASTEXITCODE) { throw 'javac failed' }
Copy-Item (Join-Path $src 'resources\*') (Join-Path $out 'classes') -Recurse
$jar = Join-Path $root "source\files\mods\chatlift-$version.jar"
Remove-Item $jar -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') --create --file $jar -C (Join-Path $out 'classes') .
if ($LASTEXITCODE) { throw 'jar failed' }
$sha = (Get-FileHash $jar -Algorithm SHA512).Hash.ToLower()
"$jar"
"sha512 $sha"
