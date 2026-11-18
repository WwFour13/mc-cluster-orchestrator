@echo off
setlocal
cd /d "%~dp0.."

echo ========================================================
echo [MC Cluster Test] Compiling Token Diagnostic Harness...
echo ========================================================

if not exist "tests\bin" mkdir "tests\bin"

javac --release 21 -encoding UTF-8 -cp "minecraft-server\ServerLauncher.jar" -d "tests\bin" src\ClusterToken.java src\ServerLauncher.java tests\TestTokenDecoder.java
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b %ERRORLEVEL%
)

echo [OK] Compiled successfully. Launching Diagnostic Tool...
echo.

java -cp "tests\bin;minecraft-server\ServerLauncher.jar" TestTokenDecoder %*

pause
