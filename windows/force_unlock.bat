@echo off
title Emergency Cluster Unlock
cd /d "%~dp0.."

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0unlock.ps1"
pause

