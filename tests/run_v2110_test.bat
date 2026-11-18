@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo ================================================================================
echo           MC CLUSTER ORCHESTRATOR - v2.1.10 FULL UNIT TEST SUITE
echo ================================================================================
echo.

if not exist "tests\bin" mkdir "tests\bin"

echo [Compiling Test Harnesses] Compiling 7 unit test classes...
javac --release 21 -encoding UTF-8 -cp "minecraft-server\ServerLauncher.jar" -d "tests\bin" ^
  src\ClusterToken.java ^
  src\ServerLauncher.java ^
  tests\TestCredentialIsolation.java ^
  tests\TestRcloneFlagIntegrity.java ^
  tests\TestJsonParsing.java ^
  tests\TestLockTombstoneCompatibility.java ^
  tests\TestStopSignalThrottling.java ^
  tests\TestEmergencyModeLifecycle.java ^
  tests\TestFailClosedLockRetention.java

if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b %ERRORLEVEL%
)
echo [OK] All test suites compiled cleanly.
echo.

set PASSED_COUNT=0
set FAILED_COUNT=0

call :run_test TestCredentialIsolation "Credential Isolation and B2 Remote Target"
call :run_test TestRcloneFlagIntegrity "Rclone Command Construction and Flag Integrity"
call :run_test TestJsonParsing "JSON Parsing and Boolean Extraction"
call :run_test TestLockTombstoneCompatibility "Lock Tombstone Dual-Architecture Compatibility"
call :run_test TestStopSignalThrottling "Sixty-Second Stop-Signal Throttling"
call :run_test TestEmergencyModeLifecycle "Emergency Local Mode State and Expiration"
call :run_test TestFailClosedLockRetention "Fail-Closed Lock Retention and Teardown"

echo ================================================================================
echo                          v2.1.10 TEST SCOREBOARD
echo ================================================================================
echo Total Test Suites Executed : 7
echo Passed                     : !PASSED_COUNT!
echo Failed                     : !FAILED_COUNT!
echo.

if !FAILED_COUNT! equ 0 (
    echo [STATUS] ALL v2.1.10 UNIT TESTS PASSED WITH 100%% SUCCESS.
    echo ================================================================================
    if "%~1" neq "--no-pause" pause
    exit /b 0
) else (
    echo [STATUS] ONE OR MORE UNIT TESTS FAILED.
    echo ================================================================================
    if "%~1" neq "--no-pause" pause
    exit /b 1
)

:run_test
set TEST_CLASS=%1
set TEST_NAME=%~2
java -cp "tests\bin;minecraft-server\ServerLauncher.jar" %TEST_CLASS%
if !ERRORLEVEL! equ 0 (
    set /a PASSED_COUNT+=1
) else (
    set /a FAILED_COUNT+=1
    echo [FAIL] %TEST_NAME% exited with code !ERRORLEVEL!
)
goto :eof
