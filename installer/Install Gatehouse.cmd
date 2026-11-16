@echo off
rem Runs gatehouse-install.ps1, the script beside this file (plain text: open it to see what it does).
rem It asks what to install: Gatehouse (the launcher), Gatekeeper (for the machine that runs the servers), or both.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0gatehouse-install.ps1"
echo.
pause
