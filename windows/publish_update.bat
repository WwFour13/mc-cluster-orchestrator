@echo off
setlocal enabledelayedexpansion
title Publish Server Update to Cluster
cd /d "%~dp0.."
set "ORCH_DIR=%cd%"

echo ========================================================
echo [MC Cluster Orchestrator] Building and Publishing Update
echo ========================================================

:: 1. Trigger compiler first so ServerLauncher.jar is always current
if exist "%ORCH_DIR%\windows\build.bat" (
    echo [1/2] Compiling latest orchestrator binary...
    call "%ORCH_DIR%\windows\build.bat"
    if !ERRORLEVEL! neq 0 (
        echo [ERROR] Build failed. Aborting publish.
        pause
        exit /b !ERRORLEVEL!
    )
)

:: 2. Publish to Backblaze B2
cd /d "%ORCH_DIR%\minecraft-server"
echo.
echo [2/2] Publishing administrative update to Backblaze B2...
java -jar ServerLauncher.jar publish-update
pause
