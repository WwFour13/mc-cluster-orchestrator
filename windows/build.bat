@echo off
setlocal enabledelayedexpansion
title Build MC Cluster Orchestrator

cd /d "%~dp0.."
set "ORCH_DIR=%cd%"

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
set "TARGET_DIR=%ORCH_DIR%\minecraft-server"
if not exist "!TARGET_DIR!" (
    echo [INFO] 'minecraft-server' directory not found. Creating it at '!TARGET_DIR!'...
    mkdir "!TARGET_DIR!"
)

:: 3. Compile Java Sources
echo [1/3] Compiling Java source files...
if exist "%ORCH_DIR%\build_out" rd /s /q "%ORCH_DIR%\build_out"
mkdir "%ORCH_DIR%\build_out\META-INF"

dir /b /s "%ORCH_DIR%\src\*.java" > "%ORCH_DIR%\sources.txt"
"%JAVAC_BIN%" --release 21 -encoding UTF-8 -d "%ORCH_DIR%\build_out" @"%ORCH_DIR%\sources.txt"
set "COMPILE_STATUS=%ERRORLEVEL%"
if exist "%ORCH_DIR%\sources.txt" del "%ORCH_DIR%\sources.txt"

if !COMPILE_STATUS! neq 0 (
    echo [ERROR] Compilation failed.
    rd /s /q "%ORCH_DIR%\build_out"
    pause
    exit /b !COMPILE_STATUS!
)

:: 4. Write Manifest & Package ServerLauncher.jar
echo [2/3] Packaging ServerLauncher.jar...
(
    echo Manifest-Version: 1.0
    echo Main-Class: ServerLauncher
    echo.
) > "%ORCH_DIR%\build_out\META-INF\MANIFEST.MF"

powershell -NoProfile -ExecutionPolicy Bypass -File "%ORCH_DIR%\windows\package_jar.ps1" -SourceDir "%ORCH_DIR%\build_out" -DestJar "!TARGET_DIR!\ServerLauncher.jar"
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
    
    :: Clean up legacy subdirectories if present
    if exist "!TARGET_DIR!\windows" rd /s /q "!TARGET_DIR!\windows"
    if exist "!TARGET_DIR!\mac-linux" rd /s /q "!TARGET_DIR!\mac-linux"

    :: Deploy launchers to root
    if exist "%ORCH_DIR%\templates\windows\START_SERVER.bat" copy /y "%ORCH_DIR%\templates\windows\START_SERVER.bat" "!TARGET_DIR!\START_SERVER.bat" >nul
    if exist "%ORCH_DIR%\templates\windows\Setup_Desktop_Shortcut.bat" copy /y "%ORCH_DIR%\templates\windows\Setup_Desktop_Shortcut.bat" "!TARGET_DIR!\Setup_Desktop_Shortcut.bat" >nul
    if exist "%ORCH_DIR%\templates\windows\Install_Minecraft_Cluster.bat" copy /y "%ORCH_DIR%\templates\windows\Install_Minecraft_Cluster.bat" "!TARGET_DIR!\Install_Minecraft_Cluster.bat" >nul
    if exist "%ORCH_DIR%\templates\mac-linux\START_SERVER.command" copy /y "%ORCH_DIR%\templates\mac-linux\START_SERVER.command" "!TARGET_DIR!\START_SERVER.command" >nul
    if exist "%ORCH_DIR%\templates\mac-linux\Setup_Mac_App.command" copy /y "%ORCH_DIR%\templates\mac-linux\Setup_Mac_App.command" "!TARGET_DIR!\Setup_Mac_App.command" >nul
    if exist "%ORCH_DIR%\templates\mac-linux\Install_Minecraft_Cluster.command" copy /y "%ORCH_DIR%\templates\mac-linux\Install_Minecraft_Cluster.command" "!TARGET_DIR!\Install_Minecraft_Cluster.command" >nul
    echo   [OK] Deployed primary launchers and installers

    :: Deploy tools/ folder
    if not exist "!TARGET_DIR!\tools" mkdir "!TARGET_DIR!\tools"
    if exist "%ORCH_DIR%\templates\windows\tools" copy /y "%ORCH_DIR%\templates\windows\tools\*" "!TARGET_DIR!\tools\" >nul
    if exist "%ORCH_DIR%\templates\mac-linux\tools" copy /y "%ORCH_DIR%\templates\mac-linux\tools\*" "!TARGET_DIR!\tools\" >nul
    echo   [OK] Deployed tools\ scripts

    :: Deploy root templates
    if exist "%ORCH_DIR%\templates\icon.ico" (
        if not exist "!TARGET_DIR!\bin" mkdir "!TARGET_DIR!\bin"
        copy /y "%ORCH_DIR%\templates\icon.ico" "!TARGET_DIR!\bin\icon.ico" >nul
        echo   [OK] Deployed bin\icon.ico
    )
    if exist "%ORCH_DIR%\templates\rclone-filter.txt" (
        copy /y "%ORCH_DIR%\templates\rclone-filter.txt" "!TARGET_DIR!\rclone-filter.txt" >nul
        echo   [OK] Deployed rclone-filter.txt
    )
    if exist "%ORCH_DIR%\templates\admin-dist-filter.txt" (
        copy /y "%ORCH_DIR%\templates\admin-dist-filter.txt" "!TARGET_DIR!\admin-dist-filter.txt" >nul
        echo   [OK] Deployed admin-dist-filter.txt
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

