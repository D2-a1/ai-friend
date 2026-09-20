@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-task-semantic-model-evaluation.ps1" %*
exit /b %ERRORLEVEL%
