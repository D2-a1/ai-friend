@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-funasr-training.ps1" %*
set "trainingExitCode=%ERRORLEVEL%"
echo.
pause
exit /b %trainingExitCode%
