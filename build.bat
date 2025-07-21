@echo off
setlocal enabledelayedexpansion

:: Initialize error flag
set "ERROR_OCCURRED=0"

echo Building release version...
call "%~dp0\gradlew" assembleRelease --no-daemon
if errorlevel 1 (
    echo Error: Gradle build failed
    set "ERROR_OCCURRED=1"
    goto :end
)

echo Generating JAR file...
call "%~dp0\jar\genJar.bat" %1
if errorlevel 1 (
    echo Error: JAR generation failed
    set "ERROR_OCCURRED=1"
    goto :end
)

:end
if "%ERROR_OCCURRED%"=="1" (
    echo Build process completed with errors
) else (
    echo Build process completed successfully
)

pause