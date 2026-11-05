@echo off
title Minecraft Cluster Host
cd /d "%~dp0"

:: 1. Verify orchestrator binary exists
if not exist "ServerLauncher.jar" (
    echo [ERROR] ServerLauncher.jar not found!
    echo Ensure this folder was extracted completely from cohost-windows.zip.
    pause
    exit /b 1
)

:: 2. Launch cluster orchestrator
java -jar ServerLauncher.jar
pause

