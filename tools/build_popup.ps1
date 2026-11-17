# The launcher is the Play half of the one Gatehouse program: tools\build_program.ps1 builds it (and the Host half), and
# writes it to source\files\launch-popup.jar. This is kept so the old command still works.
#   powershell -ExecutionPolicy Bypass -File tools\build_popup.ps1
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build_program.ps1') | Out-Null
"$(Join-Path (Split-Path -Parent $PSScriptRoot) 'source\files\launch-popup.jar')"
