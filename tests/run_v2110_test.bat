@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "DO_PAUSE=1"
if /i "%~1"=="--no-pause" set "DO_PAUSE=0"
if /i "%~2"=="--no-pause" set "DO_PAUSE=0"

echo ================================================================================
echo           MC CLUSTER ORCHESTRATOR - v2.1.13 FULL UNIT TEST SUITE
echo ================================================================================
echo.

if not exist "tests\bin" mkdir "tests\bin"

echo [Compiling Test Harnesses] Compiling domain-separated test classes...
javac --release 21 -encoding UTF-8 -cp "minecraft-server\ServerLauncher.jar;tests\common;tests\local;tests\backblaze;tests\playit;src" -d "tests\bin" ^
  src\*.java ^
  tests\common\*.java ^
  tests\local\*.java ^
  tests\backblaze\*.java ^
  tests\playit\*.java

if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    if "%DO_PAUSE%"=="1" pause
    exit /b %ERRORLEVEL%
)
echo [OK] All domain test suites compiled cleanly.
echo.

set PASSED_COUNT=0
set FAILED_COUNT=0

echo [DOMAIN 1] Local Offline, Packaging and Lifecycle Tests (tests/local/)
echo --------------------------------------------------------------------------------
call :run_test TestSecretLeakSanitizer "Personal Secret Leak and Git Tracking Sanitizer"
call :run_test TestStarterPackLifecycle "Starter Pack Offline Export/Import Lifecycle"
call :run_test TestAdminOnboarding "First-Time Admin Onboarding and Server Adoption"
call :run_test TestJsonParsing "JSON Parsing and Boolean Extraction"
call :run_test TestDirectVersionComparator "Direct SemVer Hierarchy Invariants"
call :run_test TestDirectWorldEmpty "Direct World Population and Chunk Boundary Sniffer"
call :run_test TestDirectWorldSafety "Dual Persistence and level.dat Conflict Guard"

echo.
echo [DOMAIN 2] Backblaze B2, Concurrency and Quota Protection Tests (tests/backblaze/)
echo --------------------------------------------------------------------------------
call :run_test TestCredentialIsolation "Credential Isolation and B2 Remote Target"
call :run_test TestRcloneFlagIntegrity "Rclone Command Construction and Flag Integrity"
call :run_test TestLockTombstoneCompatibility "Lock Tombstone Dual-Architecture Compatibility"
call :run_test TestStopSignalThrottling "Sixty-Second Stop-Signal Throttling"
call :run_test TestEmergencyModeLifecycle "Emergency Local Mode State and Expiration"
call :run_test TestFailClosedLockRetention "Fail-Closed Lock Retention and Teardown"
call :run_test TestDirectQuotaProtection "Direct Quota Protection and Egress Guard"
call :run_test TestDirectLockProtocol "Direct Lock Protocol and Distributed Quiescence"
call :run_test TestStaleHostLifecycle "Stale-Host Fail-Stop and Lease Lifecycle Invariants"

echo.
echo [DOMAIN 3] Playit Anycast Tunnel Resolution Tests (tests/playit/)
echo --------------------------------------------------------------------------------
call :run_test TestPlayitTunnelParsing "Playit Anycast Tunnel SRV and Binary Resolution"

echo.
echo ================================================================================
echo                          v2.1.13 TEST SCOREBOARD
echo ================================================================================
echo Total Test Suites Executed : 17
echo Passed                     : !PASSED_COUNT!
echo Failed                     : !FAILED_COUNT!
echo.

if !FAILED_COUNT! equ 0 (
    echo [STATUS] ALL 17 DOMAIN UNIT TESTS PASSED WITH 100%% SUCCESS.
    echo ================================================================================
    if "%DO_PAUSE%"=="1" pause
    exit /b 0
) else (
    echo [STATUS] ONE OR MORE UNIT TESTS FAILED.
    echo ================================================================================
    if "%DO_PAUSE%"=="1" pause
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