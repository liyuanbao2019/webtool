@echo off
echo ==========================================
echo  XjTool Quick Start Script (Port 9090)
echo ==========================================

set /p rebuild="Do you want to rebuild the project with Maven? (y/n, default n): "
if /I "%rebuild%"=="y" (
    echo [INFO] Rebuilding project, please wait...
    call mvn package -DskipTests
    if %errorlevel% neq 0 (
        echo [ERROR] Maven build failed! Please check your code.
        pause
        exit /b %errorlevel%
    )
    echo [SUCCESS] Build completed successfully!
)

echo [INFO] Starting XjTool Service...
REM Boot it in a new window so you can monitor logs or hit Ctrl+C to terminate
start "XjTool-9090 Console" java -jar target/webtool-1.0.0-SNAPSHOT.jar
echo.
echo [SUCCESS] Startup command sent! Running in a separate console window.
echo Please visit: http://localhost:9090
echo ==========================================
timeout /t 5
