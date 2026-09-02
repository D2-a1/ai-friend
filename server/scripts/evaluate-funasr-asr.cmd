@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0evaluate-funasr-asr.ps1" %*
set "evaluateExitCode=%ERRORLEVEL%"
echo.
pause
exit /b %evaluateExitCode%
