@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-funasr-local-environment.ps1" %*
set "environmentCheckExitCode=%ERRORLEVEL%"
echo.
pause
exit /b %environmentCheckExitCode%
