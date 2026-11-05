@echo off
title Package Cohost Starter Pack
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package_cohost_pack.ps1"
pause

