@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0connect-vps.ps1" %*
if errorlevel 1 pause
