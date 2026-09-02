@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-funasr-inference.ps1" %*
set inferenceExitCode=%ERRORLEVEL%
endlocal & exit /b %inferenceExitCode%
