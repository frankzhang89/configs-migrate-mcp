@echo off
rem Keep the PowerShell helper beside this launcher for double-click support.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0configs-migrate.ps1" %*
exit /b %ERRORLEVEL%
