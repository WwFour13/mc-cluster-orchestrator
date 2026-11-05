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
    if exist "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin\javac.exe" (
        set "JAVAC_BIN=%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin\javac.exe"
    ) else if exist "C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.2\jbr\bin\javac.exe" (
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
    echo [INFO] 'minecraft-server' directory not found. Creating it at '%ORCH_DIR%\minecraft-server'...
    mkdir "%ORCH_DIR%\minecraft-server"
    set "TARGET_DIR=%ORCH_DIR%\minecraft-server"
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
    echo Main-Class: ServerLauncher
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

:: 6. Auto-provision operational templates into deployment folder
if exist "%ORCH_DIR%\templates" (
    echo.
    echo ========================================================
    echo [MC Cluster Orchestrator] Deploying operational templates...
    echo ========================================================
    if exist "%ORCH_DIR%\templates\Setup_Desktop_Shortcut.bat" (
        copy /y "%ORCH_DIR%\templates\Setup_Desktop_Shortcut.bat" "!TARGET_DIR!\Setup_Desktop_Shortcut.bat" >nul
        echo   [OK] Deployed Setup_Desktop_Shortcut.bat
    )
    if exist "%ORCH_DIR%\templates\setup_shortcut.ps1" (
        copy /y "%ORCH_DIR%\templates\setup_shortcut.ps1" "!TARGET_DIR!\setup_shortcut.ps1" >nul
        echo   [OK] Deployed setup_shortcut.ps1
    )
    if exist "%ORCH_DIR%\templates\Setup_Mac_App.sh" (
        copy /y "%ORCH_DIR%\templates\Setup_Mac_App.sh" "!TARGET_DIR!\Setup_Mac_App.sh" >nul
        echo   [OK] Deployed Setup_Mac_App.sh
    )
    if exist "%ORCH_DIR%\templates\icon.ico" (
        if not exist "!TARGET_DIR!\bin" mkdir "!TARGET_DIR!\bin"
        copy /y "%ORCH_DIR%\templates\icon.ico" "!TARGET_DIR!\bin\icon.ico" >nul
        echo   [OK] Deployed bin\icon.ico
    )
    if exist "%ORCH_DIR%\templates\run_server.bat" (
        copy /y "%ORCH_DIR%\templates\run_server.bat" "!TARGET_DIR!\run_server.bat" >nul
        echo   [OK] Deployed run_server.bat (Windows)
    )
    if exist "%ORCH_DIR%\templates\run_server.sh" (
        copy /y "%ORCH_DIR%\templates\run_server.sh" "!TARGET_DIR!\run_server.sh" >nul
        echo   [OK] Deployed run_server.sh (Linux/macOS)
    )
    if exist "%ORCH_DIR%\templates\sync_pull.bat" (
        copy /y "%ORCH_DIR%\templates\sync_pull.bat" "!TARGET_DIR!\sync_pull.bat" >nul
        echo   [OK] Deployed sync_pull.bat
    )
    if exist "%ORCH_DIR%\templates\sync_push.bat" (
        copy /y "%ORCH_DIR%\templates\sync_push.bat" "!TARGET_DIR!\sync_push.bat" >nul
        echo   [OK] Deployed sync_push.bat
    )
    if exist "%ORCH_DIR%\templates\sync_pull.sh" (
        copy /y "%ORCH_DIR%\templates\sync_pull.sh" "!TARGET_DIR!\sync_pull.sh" >nul
        echo   [OK] Deployed sync_pull.sh
    )
    if exist "%ORCH_DIR%\templates\sync_push.sh" (
        copy /y "%ORCH_DIR%\templates\sync_push.sh" "!TARGET_DIR!\sync_push.sh" >nul
        echo   [OK] Deployed sync_push.sh
    )
    if exist "%ORCH_DIR%\templates\rclone-filter.txt" (
        copy /y "%ORCH_DIR%\templates\rclone-filter.txt" "!TARGET_DIR!\rclone-filter.txt" >nul
        echo   [OK] Deployed rclone-filter.txt
    )
    if not exist "!TARGET_DIR!\config.json" (
        if exist "%ORCH_DIR%\templates\config.example.json" (
            copy /y "%ORCH_DIR%\templates\config.example.json" "!TARGET_DIR!\config.json" >nul
            echo   [OK] Initialized config.json (Please configure with your Backblaze B2 keys)
        )
    )
    if not exist "!TARGET_DIR!\[template]config.json" (
        if exist "%ORCH_DIR%\templates\config.example.json" (
            copy /y "%ORCH_DIR%\templates\config.example.json" "!TARGET_DIR!\[template]config.json" >nul
            echo   [OK] Deployed [template]config.json for co-hosts
        )
    )
)

echo.
echo ========================================================
echo [SUCCESS] Full cluster build completed!
echo   [OK] ServerLauncher.jar : !TARGET_DIR!\ServerLauncher.jar
if exist "!TARGET_DIR!\plugins\ClusterGuard.jar" (
    echo   [OK] ClusterGuard.jar   : !TARGET_DIR!\plugins\ClusterGuard.jar
)
echo ========================================================
ping -n 3 127.0.0.1 >nul 2>&1
