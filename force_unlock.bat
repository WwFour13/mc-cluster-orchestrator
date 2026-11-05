@echo off
title Emergency Cluster Unlock
cd /d "%~dp0"

echo [Emergency Unlock] Attempting cloud and local unlock...
if exist "%~dp0minecraft-server\ServerLauncher.jar" (
    java -jar "%~dp0minecraft-server\ServerLauncher.jar" unlock
) else if exist "%~dp0ServerLauncher.jar" (
    java -jar "%~dp0ServerLauncher.jar" unlock
) else (
    echo [INFO] Falling back to deleting local lock files...
    if exist "%~dp0minecraft-server\.server_active.lock" del /f /q "%~dp0minecraft-server\.server_active.lock"
    if exist "%~dp0.server_active.lock" del /f /q "%~dp0.server_active.lock"
    echo [OK] Local locks cleared.
)

pause
