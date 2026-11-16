@echo off
rem Runs run-backend.ps1 in PowerShell, so it works from Command Prompt, a double-click or any terminal.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-backend.ps1"
pause
