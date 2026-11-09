@echo off
setlocal enabledelayedexpansion
title Minecraft Cluster Setup & Installer
cd /d "%~dp0"

echo ========================================================
echo        MINECRAFT CLUSTER ORCHESTRATOR INSTALLER
echo ========================================================
echo.

:: 1. Verify Java 17+ or 21+
echo [1/3] Checking Java runtime environment...
set "JAVA_VALID=0"

where java >nul 2>&1
if %ERRORLEVEL% equ 0 (
    for /f "tokens=*" %%v in ('powershell -NoProfile -Command "$v = (java -version 2>&1 | Select-String 'version \"' | ForEach-Object { ($_ -split '\"')[1] }); if (-not $v) { exit 1 }; $parts = $v.Split('.'); $major = if ($parts[0] -eq '1') { [int]$parts[1] } else { [int]$parts[0] }; if ($major -ge 17) { Write-Output $major; exit 0 } else { exit 2 }"') do (
        set "DETECTED_JAVA_VER=%%v"
        set "JAVA_VALID=1"
    )
)

if "!JAVA_VALID!"=="1" (
    echo   [OK] Detected compatible Java !DETECTED_JAVA_VER! installation.
) else (
    echo.
    echo   [!] Compatible Java (Java 21 or 17+) was not found on your system.
    echo       Minecraft server hosting requires Java 17 or higher (Java 21 LTS recommended).
    echo.
    
    where winget >nul 2>&1
    if !ERRORLEVEL! equ 0 (
        echo   [>] Installing Eclipse Adoptium Temurin 21 JDK via Windows Package Manager...
        echo       (This may take a moment. A Windows security prompt may appear).
        winget install EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements
        if !ERRORLEVEL! equ 0 (
            echo   [OK] Java 21 JDK successfully installed!
            :: Update PATH for current session
            for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21*") do (
                if exist "%%D\bin\java.exe" set "PATH=%%D\bin;!PATH!"
            )
        ) else (
            echo   [WARN] Automatic winget installation did not complete cleanly.
            goto :manual_java
        )
    ) else (
        :manual_java
        echo   [>] Opening Eclipse Adoptium Java 21 LTS download in your default browser...
        start https://adoptium.net/temurin/releases/?version=21
        echo.
        echo   Please complete the Java installer setup, then return here.
        echo.
        pause
    )
)

:: 2. Configure Desktop & Search Shortcuts
echo.
echo [2/3] Registering Desktop & Start Menu application shortcuts...
if exist "%~dp0tools\setup_shortcut.ps1" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\setup_shortcut.ps1"
) else (
    echo   [WARN] tools\setup_shortcut.ps1 not found. Skipping shortcut registration.
)

:: 3. Launch Application
echo.
echo [3/3] Launching Minecraft Cluster Orchestrator...
if exist "%~dp0START_SERVER.bat" (
    start "" "%~dp0START_SERVER.bat"
) else if exist "%~dp0ServerLauncher.jar" (
    start "" javaw -jar "%~dp0ServerLauncher.jar"
)

echo.
echo ========================================================
echo  [SUCCESS] All done! You can close this installer window.
echo ========================================================
timeout /t 3 >nul
exit /b 0
