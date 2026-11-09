@echo off
title Push ServerLauncher Update to Cloud
cd /d "%~dp0\..\minecraft-server"

if not exist "ServerLauncher.jar" (
    echo [ERROR] ServerLauncher.jar not found in minecraft-server! Run windows\build.bat first.
    pause
    exit /b 1
)

echo ========================================================
echo [MC Cluster] Pushing ServerLauncher.jar to Backblaze B2...
echo ========================================================
java -jar ServerLauncher.jar push-launcher
pause
