@echo off
setlocal
cd /d "%~dp0"
set "POWERSHELL_CMD=powershell.exe"
where pwsh.exe >nul 2>nul && set "POWERSHELL_CMD=pwsh.exe"
%POWERSHELL_CMD% -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start-dev.ps1" -Action stop
if errorlevel 1 pause
endlocal
