@echo off
cd /d "%~dp0"
call "%~dp0LAUNCH_ORCHESTRATOR.bat" %*
exit /b %ERRORLEVEL%
