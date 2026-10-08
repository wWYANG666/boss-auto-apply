@echo off
setlocal
cd /d "%~dp0"

set "POWERSHELL_CMD=powershell.exe"
where pwsh.exe >nul 2>nul && set "POWERSHELL_CMD=pwsh.exe"

echo Stopping existing CareerLens services...
%POWERSHELL_CMD% -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start-dev.ps1" -Action stop
if errorlevel 1 (
  echo.
  echo [ERROR] CareerLens shutdown failed. Startup cancelled.
  pause
  exit /b 1
)

echo Starting CareerLens with an empty local workspace...
%POWERSHELL_CMD% -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start-dev.ps1" -OpenBrowser
if errorlevel 1 (
  echo.
  echo [ERROR] CareerLens startup failed. Review the message above.
  pause
  exit /b 1
)

echo.
echo CareerLens is ready. You may close this window.
endlocal
