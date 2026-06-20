@echo off
title ChatApp Secure Server
cd /d "%~dp0"

if not exist "%~dp0caddy.exe" (
    echo ERROR: caddy.exe was not found beside this launcher.
    echo Place caddy.exe in: %~dp0
    pause
    exit /b 1
)

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

echo Starting encrypted HTTPS and WSS proxy...
tasklist /FI "IMAGENAME eq caddy.exe" 2>NUL | find /I "caddy.exe" >NUL
if errorlevel 1 (
    start "ChatApp HTTPS" /min "%~dp0caddy.exe" run --config "%~dp0Caddyfile"
) else (
    echo Caddy is already running.
)

powershell -NoProfile -Command "$c=Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue | Select-Object -First 1; if(-not $c){exit 0}; if($c.LocalAddress -eq '127.0.0.1'){exit 10}; exit 20"
if errorlevel 20 (
    echo ERROR: An older or unrelated server is exposing port 8080 on the network.
    echo Stop it in Android Studio or close its server window, then try again.
    pause
    exit /b 1
)
if errorlevel 10 (
    echo ChatApp server is already running securely. No second copy is needed.
    echo Caddy and the server are ready.
    pause
    exit /b 0
)

echo Starting ChatApp server...
echo Keep this window open. Press Ctrl+C to stop the app server.
echo.
call gradlew.bat :server:run

echo.
echo The app server has stopped. Close the ChatApp HTTPS window too.
pause
