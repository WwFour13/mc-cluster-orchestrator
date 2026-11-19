@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

echo ================================================================================
echo           MC CLUSTER ORCHESTRATOR - v2.1.11 FULL UNIT TEST SUITE
echo ================================================================================
echo.

if not exist "tests\bin" mkdir "tests\bin"

echo [Compiling Test Harnesses] Compiling 12 unit and direct-function test classes...
javac --release 21 -encoding UTF-8 -cp "minecraft-server\ServerLauncher.jar;tests" -d "tests\bin" ^
  src\ClusterToken.java ^
  src\ServerLauncher.java ^
  tests\MockRcloneHarness.java ^
  tests\TestCredentialIsolation.java ^
  tests\TestRcloneFlagIntegrity.java ^
  tests\TestJsonParsing.java ^
  tests\TestLockTombstoneCompatibility.java ^
  tests\TestStopSignalThrottling.java ^
  tests\TestEmergencyModeLifecycle.java ^
  tests\TestFailClosedLockRetention.java ^
  tests\TestDirectWorldSafety.java ^
  tests\TestDirectQuotaProtection.java ^
  tests\TestDirectLockProtocol.java ^
  tests\TestDirectWorldEmpty.java ^
  tests\TestDirectVersionComparator.java

if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b %ERRORLEVEL%
)
echo [OK] All test suites compiled cleanly.
echo.

set PASSED_COUNT=0
set FAILED_COUNT=0

echo [PHASE 1] Structural and Architectural Invariant Tests (Synthetic Harnesses)
echo --------------------------------------------------------------------------------
call :run_test TestCredentialIsolation "Credential Isolation and B2 Remote Target"
call :run_test TestRcloneFlagIntegrity "Rclone Command Construction and Flag Integrity"
call :run_test TestJsonParsing "JSON Parsing and Boolean Extraction"
call :run_test TestLockTombstoneCompatibility "Lock Tombstone Dual-Architecture Compatibility"
call :run_test TestStopSignalThrottling "Sixty-Second Stop-Signal Throttling"
call :run_test TestEmergencyModeLifecycle "Emergency Local Mode State and Expiration"
call :run_test TestFailClosedLockRetention "Fail-Closed Lock Retention and Teardown"

echo.
echo [PHASE 2] Production Direct-Function Tests (Mock Rclone Interceptor)
echo --------------------------------------------------------------------------------
call :run_test TestDirectWorldSafety "Direct ServerLauncher.pushToB2 Conflict and World Safety"
call :run_test TestDirectQuotaProtection "Direct ServerLauncher.checkRemoteStopSignal Quota Protection"
call :run_test TestDirectLockProtocol "Direct ServerLauncher Lock Protocol and Distributed Quiescence"
call :run_test TestDirectWorldEmpty "Direct ServerLauncher.isWorldEmpty Boundary Detection"
call :run_test TestDirectVersionComparator "Direct ServerLauncher.compareVersions SemVer Invariants"

echo ================================================================================
echo                          v2.1.11 TEST SCOREBOARD
echo ================================================================================
echo Total Test Suites Executed : 12
echo Passed                     : !PASSED_COUNT!
echo Failed                     : !FAILED_COUNT!
echo.

if !FAILED_COUNT! equ 0 (
    echo [STATUS] ALL v2.1.11 UNIT TESTS PASSED WITH 100%% SUCCESS.
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
