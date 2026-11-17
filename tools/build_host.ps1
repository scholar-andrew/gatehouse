# Gatekeeper is the Host half of the one Gatehouse program: tools\build_program.ps1 builds it (and the Play half), and
# writes it to host\oj-host.jar. This is kept so the old command still works.
#   powershell -ExecutionPolicy Bypass -File tools\build_host.ps1
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build_program.ps1') | Out-Null
"$(Join-Path (Split-Path -Parent $PSScriptRoot) 'host\oj-host.jar')"
