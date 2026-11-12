@echo off
title Minecraft Cluster Host
cd /d "%~dp0"

:loop
:: Apply pending orchestrator update if retrieved from cluster cloud
if exist "ServerLauncher.jar.update" (
    move /y "ServerLauncher.jar.update" "ServerLauncher.jar" >nul
)

:: 1. Verify orchestrator binary exists
if not exist "ServerLauncher.jar" (
    echo [ERROR] ServerLauncher.jar not found!
    echo Ensure this folder was extracted completely from the cluster package.
    pause
    exit /b 1
)

:: 2. Launch cluster orchestrator (GUI with console output fallback)
java -jar ServerLauncher.jar %*
set "EXIT_CODE=%ERRORLEVEL%"

:: 3. If launcher pulled an update and requested restart, loop immediately
if "%EXIT_CODE%"=="100" goto loop
if exist "ServerLauncher.jar.update" goto loop

if %EXIT_CODE% NEQ 0 (
    echo.
    echo [ERROR] Server exited with code %EXIT_CODE%
    pause
)
exit /b %EXIT_CODE%
