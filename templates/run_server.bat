@echo off
title Minecraft Cluster Host
cd /d "%~dp0"

:: 1. Verify orchestrator binary exists
if not exist "%~dp0ServerLauncher.jar" (
    echo [ERROR] ServerLauncher.jar not found!
    echo Run build.bat in mc-cluster-orchestrator to generate it.
    pause
    exit /b 1
)

:: 2. Launch cluster orchestrator (handles cloud lock, cloud pull, runtime, autosave, and cloud push)
java -jar ServerLauncher.jar
pause
