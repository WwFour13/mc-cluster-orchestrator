@echo off
title Package All GitHub Release Assets
cd /d "%~dp0.."

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package_release_assets.ps1"
echo.
pause
