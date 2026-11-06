@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-SupportIQ.ps1"
exit /b %ERRORLEVEL%
