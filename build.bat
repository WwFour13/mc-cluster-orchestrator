@echo off
setlocal enabledelayedexpansion
title Build MC Cluster Orchestrator

cd /d "%~dp0"
set "ORCH_DIR=%~dp0"
if "%ORCH_DIR:~-1%"=="\" set "ORCH_DIR=%ORCH_DIR:~0,-1%"

echo ========================================================
echo [MC Cluster Orchestrator] Building ServerLauncher...
echo ========================================================

:: 1. Detect javac
set "JAVAC_BIN=javac"
where javac >nul 2>&1
if %ERRORLEVEL% neq 0 (
    if exist "C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.2\jbr\bin\javac.exe" (
        set "JAVAC_BIN=C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.2\jbr\bin\javac.exe"
    ) else (
        echo [ERROR] 'javac' compiler not found on PATH or standard JDK directories.
        echo Please ensure JDK 11+ is installed.
        pause
        exit /b 1
    )
)

:: 2. Resolve destination directory
set "TARGET_DIR="
if exist "%ORCH_DIR%\..\minecraft-server" set "TARGET_DIR=%ORCH_DIR%\..\minecraft-server"
if exist "%ORCH_DIR%\minecraft-server" set "TARGET_DIR=%ORCH_DIR%\minecraft-server"
if "!TARGET_DIR!"=="" (
    echo [WARN] Neither '..\minecraft-server' nor 'minecraft-server' directory found.
    set "TARGET_DIR=%ORCH_DIR%"
)

:: 3. Compile ServerLauncher.java
echo [1/3] Compiling ServerLauncher.java...
if exist "%ORCH_DIR%\build_out" rd /s /q "%ORCH_DIR%\build_out"
mkdir "%ORCH_DIR%\build_out\META-INF"

"%JAVAC_BIN%" -encoding UTF-8 -d "%ORCH_DIR%\build_out" "%ORCH_DIR%\src\ServerLauncher.java"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    rd /s /q "%ORCH_DIR%\build_out"
    pause
    exit /b %ERRORLEVEL%
)

:: 4. Write Manifest & Package ServerLauncher.jar
echo [2/3] Packaging ServerLauncher.jar...
(
    echo Manifest-Version: 1.0
    echo Main-Class: src.ServerLauncher
    echo.
) > "%ORCH_DIR%\build_out\META-INF\MANIFEST.MF"

powershell -NoProfile -ExecutionPolicy Bypass -File "%ORCH_DIR%\package_jar.ps1" -SourceDir "%ORCH_DIR%\build_out" -DestJar "!TARGET_DIR!\ServerLauncher.jar"
rd /s /q "%ORCH_DIR%\build_out"

if exist "!TARGET_DIR!\ServerLauncher.jar" (
    echo Successfully built and deployed ServerLauncher.jar to !TARGET_DIR!\
) else (
    echo [ERROR] Failed to package ServerLauncher.jar.
    pause
    exit /b 1
)

:: 5. Optionally build and deploy ClusterGuard plugin
if exist "%ORCH_DIR%\cluster-guard\build_guard.bat" (
    echo.
    echo ========================================================
    echo [MC Cluster Guard] Triggering ClusterGuard build...
    echo ========================================================
    call "%ORCH_DIR%\cluster-guard\build_guard.bat"
)

echo.
echo ========================================================
echo [SUCCESS] Full cluster build completed!
echo - ServerLauncher.jar -> !TARGET_DIR!\ServerLauncher.jar
if exist "!TARGET_DIR!\plugins\ClusterGuard.jar" (
    echo - ClusterGuard.jar   -> !TARGET_DIR!\plugins\ClusterGuard.jar
)
echo ========================================================
ping -n 3 127.0.0.1 >nul 2>&1
