@echo off
setlocal enabledelayedexpansion
title Build ClusterGuard Plugin

cd /d "%~dp0"
set "GUARD_DIR=%~dp0"
if "%GUARD_DIR:~-1%"=="\" set "GUARD_DIR=%GUARD_DIR:~0,-1%"

echo ========================================================
echo [ClusterGuard] Compiling Fail-Safe Guard Plugin...
echo ========================================================

:: 1. Detect javac
set "JAVAC_BIN=javac"
where javac >nul 2>&1
if %ERRORLEVEL% neq 0 (
    if exist "C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.2\jbr\bin\javac.exe" (
        set "JAVAC_BIN=C:\Program Files\JetBrains\PyCharm Community Edition 2024.3.2\jbr\bin\javac.exe"
    ) else (
        echo [ERROR] 'javac' not found on PATH or standard JDK directories.
        echo Please ensure JDK 17+ is installed.
        pause
        exit /b 1
    )
)

:: 2. Resolve Classpath: core binary, libraries, or bundled paper-api
set "CP="
if exist "%GUARD_DIR%\..\..\minecraft-server\bin\core.dat" (
    for %%A in ("%GUARD_DIR%\..\..\minecraft-server\bin\core.dat") do if %%~zA gtr 1000 set "CP=%GUARD_DIR%\..\..\minecraft-server\bin\core.dat"
)
if exist "%GUARD_DIR%\..\minecraft-server\bin\core.dat" (
    for %%A in ("%GUARD_DIR%\..\minecraft-server\bin\core.dat") do if %%~zA gtr 1000 set "CP=%GUARD_DIR%\..\minecraft-server\bin\core.dat"
)
if exist "%GUARD_DIR%\lib\paper-api-1.20.4.jar" (
    if "!CP!"=="" (set "CP=%GUARD_DIR%\lib\paper-api-1.20.4.jar") else (set "CP=!CP!;%GUARD_DIR%\lib\paper-api-1.20.4.jar")
)

echo [1/3] Using API classpath: !CP!

:: 3. Prepare build output directory
if exist "%GUARD_DIR%\target-build" rd /s /q "%GUARD_DIR%\target-build"
mkdir "%GUARD_DIR%\target-build"

echo [2/3] Compiling ClusterGuard.java...
"%JAVAC_BIN%" -encoding UTF-8 -cp "!CP!" -d "%GUARD_DIR%\target-build" "%GUARD_DIR%\src\main\java\com\cluster\guard\ClusterGuard.java"
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Plugin compilation failed!
    rd /s /q "%GUARD_DIR%\target-build"
    pause
    exit /b %ERRORLEVEL%
)

copy /y "%GUARD_DIR%\src\main\resources\plugin.yml" "%GUARD_DIR%\target-build\plugin.yml" >nul

:: 4. Resolve destination
set "PLUGINS_DEST="
if exist "%GUARD_DIR%\..\..\minecraft-server\plugins" set "PLUGINS_DEST=%GUARD_DIR%\..\..\minecraft-server\plugins"
if exist "%GUARD_DIR%\..\minecraft-server\plugins" set "PLUGINS_DEST=%GUARD_DIR%\..\minecraft-server\plugins"
if "!PLUGINS_DEST!"=="" (
    if exist "%GUARD_DIR%\..\..\minecraft-server" (
        mkdir "%GUARD_DIR%\..\..\minecraft-server\plugins"
        set "PLUGINS_DEST=%GUARD_DIR%\..\..\minecraft-server\plugins"
    ) else if exist "%GUARD_DIR%\..\minecraft-server" (
        mkdir "%GUARD_DIR%\..\minecraft-server\plugins"
        set "PLUGINS_DEST=%GUARD_DIR%\..\minecraft-server\plugins"
    ) else (
        set "PLUGINS_DEST=%GUARD_DIR%"
    )
)

echo [3/3] Packaging and deploying ClusterGuard.jar...
powershell -NoProfile -ExecutionPolicy Bypass -File "%GUARD_DIR%\..\package_jar.ps1" -SourceDir "%GUARD_DIR%\target-build" -DestJar "!PLUGINS_DEST!\ClusterGuard.jar"
rd /s /q "%GUARD_DIR%\target-build"

echo [SUCCESS] ClusterGuard plugin built and deployed to !PLUGINS_DEST!\ClusterGuard.jar
