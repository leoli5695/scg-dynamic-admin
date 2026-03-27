@echo off
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "stress_test.ps1"
pause