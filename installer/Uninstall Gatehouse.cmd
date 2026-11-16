@echo off
rem Runs gatehouse-install.ps1 -Uninstall, the script beside this file (plain text: open it to see what it does).
rem It asks what to remove: Gatehouse (the launcher), Gatekeeper, or both. Prism Launcher and your games are left alone.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0gatehouse-install.ps1" -Uninstall
echo.
pause
