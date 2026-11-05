@echo off
title Minecraft Cluster - Setup Desktop & Start Menu Shortcut
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup_shortcut.ps1"
echo.
pause

