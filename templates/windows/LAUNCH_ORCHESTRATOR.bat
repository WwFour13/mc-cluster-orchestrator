@echo off
title Minecraft Cluster Orchestrator
cd /d "%~dp0"

:loop
:: Apply pending orchestrator update if retrieved from cluster cloud or GitHub
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

:: 2. If CLI flags or maintenance commands passed, run in console
if not "%1"=="" (
    title Minecraft Cluster Host
    java -jar ServerLauncher.jar %*
    set "EXIT_CODE=%ERRORLEVEL%"
    if "%EXIT_CODE%"=="100" goto loop
    if exist "ServerLauncher.jar.update" goto loop
    pause
    exit /b 0
)

:: 3. Launch Modern GUI cleanly with javaw (no black console window)
start "" javaw -jar ServerLauncher.jar
exit /b 0
