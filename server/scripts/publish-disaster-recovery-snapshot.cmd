@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-disaster-recovery-snapshot.ps1"
set "publishExitCode=%ERRORLEVEL%"
echo.
pause
exit /b %publishExitCode%
