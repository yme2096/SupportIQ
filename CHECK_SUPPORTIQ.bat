@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Check-SupportIQ.ps1"
exit /b %ERRORLEVEL%
