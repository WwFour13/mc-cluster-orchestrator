@echo off
title Admin - Remote Stop Server
cd /d "%~dp0"

echo ========================================================
echo  MC Cluster - Admin Remote Server Stop
echo ========================================================
echo.
echo This command signals the currently active host to save
echo all progress, warn players, and gracefully shut down.
echo.
set /p REASON="Enter shutdown reason (or press Enter for 'Admin maintenance'): "
if "%REASON%"=="" set REASON=Admin maintenance

echo.
if exist "%~dp0minecraft-server\ServerLauncher.jar" (
    java -jar "%~dp0minecraft-server\ServerLauncher.jar" remote-stop "%REASON%"
) else if exist "%~dp0ServerLauncher.jar" (
    java -jar "%~dp0ServerLauncher.jar" remote-stop "%REASON%"
) else (
    echo [ERROR] ServerLauncher.jar not found! Run build.bat first.
)

echo.
pause
