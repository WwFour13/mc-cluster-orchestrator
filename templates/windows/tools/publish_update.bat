@echo off
title Publish Server Update to Cluster
cd /d "%~dp0\.."

if not exist "ServerLauncher.jar" (
    echo [ERROR] ServerLauncher.jar not found!
    pause
    exit /b 1
)

echo ========================================================
echo [MC Cluster] Publishing Administrative Update to Cloud...
echo ========================================================
java -jar ServerLauncher.jar publish-update
pause
