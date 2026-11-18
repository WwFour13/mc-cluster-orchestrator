@echo off
setlocal
cd /d "%~dp0.."

echo ========================================================
echo [MC Cluster Test] Running Production B2/S3 Fetch Probe...
echo ========================================================
echo.

python tests\test_b2_prod_fetch.py

echo.
pause
