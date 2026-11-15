# Builds the Windows launch wrapper (mods-src\launch-wrapper\LaunchWrapper.cs) into clients\launch-wrapper.exe.
# A window-less program (/target:winexe), so no console window flashes up when the game starts.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'clients\launch-wrapper.exe'
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /optimize /target:winexe /platform:anycpu "/out:$out" (Join-Path $root 'mods-src\launch-wrapper\LaunchWrapper.cs')
if ($LASTEXITCODE) { throw 'csc failed' }
$out
