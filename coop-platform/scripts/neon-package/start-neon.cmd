@echo off
rem Double-click to start the cooperative backend connected to the Neon test database (see README.txt).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-neon.ps1" %*
echo.
echo The backend has stopped.
pause
