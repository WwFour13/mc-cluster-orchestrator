@echo off
setlocal enabledelayedexpansion
title Emergency Cluster Unlock
cd /d "%~dp0"

set "FOUND="

if exist "%~dp0minecraft-server\.server_active.lock" (
    del /f /q "%~dp0minecraft-server\.server_active.lock"
    echo [OK] Emergency cluster lock released in .\minecraft-server\.server_active.lock
    set "FOUND=1"
)

if exist "%~dp0.server_active.lock" (
    del /f /q "%~dp0.server_active.lock"
    echo [OK] Emergency cluster lock released in .\.server_active.lock
    set "FOUND=1"
)

if exist "%~dp0..\minecraft-server\.server_active.lock" (
    del /f /q "%~dp0..\minecraft-server\.server_active.lock"
    echo [OK] Emergency cluster lock released in ..\minecraft-server\.server_active.lock
    set "FOUND=1"
)

if "!FOUND!"=="" (
    echo [INFO] No active cluster lock found (.server_active.lock).
)

echo.
pause

