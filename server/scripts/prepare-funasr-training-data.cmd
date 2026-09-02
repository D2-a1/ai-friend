@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-funasr-training-data.ps1" %*
set "prepareExitCode=%ERRORLEVEL%"
echo.
pause
exit /b %prepareExitCode%
