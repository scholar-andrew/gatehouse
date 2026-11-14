# Builds one of the pack's own small mods (mods-src/<name>, a Fabric mod: chatlift or handofffix) into
# source/files/mods/<name>-<version>.jar with plain javac. Minecraft 26.x is not obfuscated, so no Loom or refmap is
# needed. Compiles against this PC's Prism Launcher libraries and the october-journey instance's mods (for Controlify).
#   powershell -ExecutionPolicy Bypass -File tools\build_mod.ps1 chatlift
# The version comes from src/main/resources/fabric.mod.json (or gradle.properties when that says "${version}").
# Afterwards: point the mod's entry in source/mods.json at the new filename and SHA-512 (printed below), remove the old
# jar, and run tools/build_pack.py. See docs/how-it-works.md, "Changing one of the pack's own mods".
param([Parameter(Mandatory = $true)][string]$Name)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mod = Join-Path $root "mods-src\$Name"
$src = Join-Path $mod 'src\main'
if (-not (Test-Path $src)) { throw "no mod source at $mod" }
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin'
$prism = Join-Path $env:APPDATA 'PrismLauncher'
$modJson = Get-Content (Join-Path $src 'resources\fabric.mod.json') -Raw
$version = ($modJson | ConvertFrom-Json).version
if ($version -eq '${version}') {
    $version = ((Get-Content (Join-Path $mod 'gradle.properties')) -match '^version=' -replace '^version=', '').Trim()
}
$cp = @(Get-ChildItem (Join-Path $prism 'libraries') -Recurse -Filter *.jar | Where-Object { $_.Name -notmatch 'natives' } | ForEach-Object FullName)
$cp += Get-ChildItem (Join-Path $prism 'instances\october-journey\minecraft\mods') -Filter '*.jar' | ForEach-Object FullName
$out = Join-Path $env:TEMP "packmod-build-$Name"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null
$argFile = Join-Path $out 'javac.args'
$lines = @('-encoding', 'UTF-8', '--release', '25', '-proc:none', '-d', "`"$($out -replace '\\','/')/classes`"", '-cp', "`"$(($cp -join ';') -replace '\\','/')`"")
$lines += Get-ChildItem (Join-Path $src 'java') -Recurse -Filter *.java | ForEach-Object { "`"$($_.FullName -replace '\\','/')`"" }
[IO.File]::WriteAllLines($argFile, $lines)
& (Join-Path $jdk 'javac.exe') "@$argFile"
if ($LASTEXITCODE) { throw 'javac failed' }
Copy-Item (Join-Path $src 'resources\*') (Join-Path $out 'classes') -Recurse
$built = Join-Path $out 'classes\fabric.mod.json'
[IO.File]::WriteAllText($built, $modJson.Replace('${version}', $version), (New-Object Text.UTF8Encoding($false)))
$jar = Join-Path $root "source\files\mods\$Name-$version.jar"
Remove-Item $jar -ErrorAction SilentlyContinue
& (Join-Path $jdk 'jar.exe') --create --file $jar -C (Join-Path $out 'classes') .
if ($LASTEXITCODE) { throw 'jar failed' }
"$jar"
"sha512 $((Get-FileHash $jar -Algorithm SHA512).Hash.ToLower())"
