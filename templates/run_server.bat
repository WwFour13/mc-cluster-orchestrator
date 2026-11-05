@echo off
title Minecraft Cluster Host
cd /d "%~dp0"

:: 1. Auto-start Syncthing daemon if not already listening on port 8384
netstat -ano | findstr :8384 >nul
if errorlevel 1 (
    echo [INFO] Syncthing is not running. Starting daemon in background...
    if exist "%APPDATA%\SyncTrayzor\syncthing.exe" (
        start "" "%APPDATA%\SyncTrayzor\syncthing.exe" -no-browser
    ) else if exist "C:\Program Files\SyncTrayzor\syncthing.exe" (
        start "" "C:\Program Files\SyncTrayzor\syncthing.exe" -no-browser
    ) else (
        echo [WARN] Syncthing executable not found in default paths.
    )
    timeout /t 3 >nul
)

:: 2. Verify orchestrator binary exists
if not exist "%~dp0ServerLauncher.jar" (
    echo [ERROR] ServerLauncher.jar not found!
    echo Run build.bat in mc-cluster-orchestrator to generate it.
    pause
    exit /b 1
)

:: 3. Launch cluster orchestrator
java -jar ServerLauncher.jar
pause

