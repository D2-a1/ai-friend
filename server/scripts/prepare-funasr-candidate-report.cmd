@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-funasr-candidate-report.ps1" %*
set candidateReportExitCode=%ERRORLEVEL%
endlocal & exit /b %candidateReportExitCode%
