@echo off
setlocal
where pwsh.exe >nul 2>nul
if not errorlevel 1 (
  pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-android.ps1"
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-android.ps1"
)
if errorlevel 1 pause
