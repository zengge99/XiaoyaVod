@echo off
setlocal enabledelayedexpansion

set "ERROR_OCCURRED=0"

:: Delete existing files
del "%~dp0\custom_spider.jar" >nul 2>&1
rd /s/q "%~dp0\Smali_classes" >nul 2>&1

:: Decompile dex to smali
java -jar "%~dp0\3rd\baksmali-2.5.2.jar" d "%~dp0\..\app\build\intermediates\dex\release\minifyReleaseWithR8\classes.dex" -o "%~dp0\Smali_classes"
if errorlevel 1 (
    echo Error: Failed to decompile classes.dex
    set "ERROR_OCCURRED=1"
    goto :cleanup
)

:: Clean target directories
rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\spider" >nul 2>&1
rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\parser" >nul 2>&1
rd /s/q "%~dp0\spider.jar\smali\com\github\catvod\js" >nul 2>&1

:: Create target directory if it doesn't exist
if not exist "%~dp0\spider.jar\smali\com\github\catvod\" (
    md "%~dp0\spider.jar\smali\com\github\catvod\"
    if errorlevel 1 (
        echo Error: Failed to create target directory
        set "ERROR_OCCURRED=1"
        goto :cleanup
    )
)

:: Move smali files
move "%~dp0\Smali_classes\com\github\catvod\spider" "%~dp0\spider.jar\smali\com\github\catvod\" >nul
if errorlevel 1 (
    echo Error: Failed to move spider directory
    set "ERROR_OCCURRED=1"
    goto :cleanup
)

move "%~dp0\Smali_classes\com\github\catvod\parser" "%~dp0\spider.jar\smali\com\github\catvod\" >nul
if errorlevel 1 (
    echo Error: Failed to move parser directory
    set "ERROR_OCCURRED=1"
    goto :cleanup
)

move "%~dp0\Smali_classes\com\github\catvod\js" "%~dp0\spider.jar\smali\com\github\catvod\" >nul
if errorlevel 1 (
    echo Error: Failed to move js directory
    set "ERROR_OCCURRED=1"
    goto :cleanup
)

:: Rebuild jar
java -jar "%~dp0\3rd\apktool_2.4.1.jar" b "%~dp0\spider.jar" -c
if errorlevel 1 (
    echo Error: Failed to rebuild jar
    set "ERROR_OCCURRED=1"
    goto :cleanup
)

:: Move final jar
move "%~dp0\spider.jar\dist\dex.jar" "%~dp0\xiaoya_proxy.jar" >nul
if errorlevel 1 (
    echo Error: Failed to move final jar
    set "ERROR_OCCURRED=1"
    goto :cleanup
)

:: Create MD5 hash
certUtil -hashfile "%~dp0\xiaoya_proxy.jar" MD5 | find /i /v "md5" | find /i /v "certutil" > "%~dp0\xiaoya_proxy.jar.md5"
if errorlevel 1 (
    echo Warning: Failed to create MD5 hash (continuing anyway)
)

:cleanup
:: Clean up directories
rd /s/q "%~dp0\spider.jar\build" >nul 2>&1
rd /s/q "%~dp0\spider.jar\smali" >nul 2>&1
rd /s/q "%~dp0\spider.jar\dist" >nul 2>&1
rd /s/q "%~dp0\Smali_classes" >nul 2>&1

:: Exit with appropriate error code
if !ERROR_OCCURRED! equ 1 (
    echo Script failed with errors
    exit /b 1
) else (
    echo Script completed successfully
    exit /b 0
)