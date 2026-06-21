@echo off
title ChatApp Server + HTTPS
cd /d "%~dp0"

if not exist "%~dp0caddy.exe" (
    echo ERROR: caddy.exe was not found beside this launcher.
    pause
    exit /b 1
)

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "CHATAPP_DATA_DIR=%~dp0server\server-data"
set "LOG_DIR=%~dp0server\server-data\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

powershell -NoProfile -Command "if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) { exit 1 }"
if errorlevel 1 (
    echo ERROR: A ChatApp server is already running on port 8080.
    echo Close the older server window, then try again.
    pause
    exit /b 1
)

taskkill /IM caddy.exe /F >NUL 2>&1

echo Starting HTTPS/WSS and ChatApp in this window...
start "" /b "%~dp0caddy.exe" run --config "%~dp0Caddyfile" > "%LOG_DIR%\caddy.log" 2>&1
timeout /t 2 /nobreak >NUL

powershell -NoProfile -Command "if (-not (Get-NetTCPConnection -State Listen -LocalPort 443 -ErrorAction SilentlyContinue)) { exit 1 }"
if errorlevel 1 (
    echo ERROR: HTTPS failed to start. Recent Caddy log:
    type "%LOG_DIR%\caddy.log"
    taskkill /IM caddy.exe /F >NUL 2>&1
    pause
    exit /b 1
)

echo HTTPS is running. Starting the app server...
echo Keep this one window open. Closing it stops both services.
echo.

call gradlew.bat :server:run

taskkill /IM caddy.exe /F >NUL 2>&1
echo.
echo ChatApp and HTTPS have stopped.
pause
