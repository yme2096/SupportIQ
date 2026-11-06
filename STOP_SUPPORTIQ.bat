@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Stop-SupportIQ.ps1"
exit /b %ERRORLEVEL%
