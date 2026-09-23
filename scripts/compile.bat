@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=all"

set "REST="
:collect
shift
if "%~1"=="" goto :parsed
set "REST=!REST! %1"
goto :collect

:parsed
if /i "%TARGET%"=="all" (
    set "TASKS=compileKotlin compileTestKotlin"
) else if /i "%TARGET%"=="engine" (
    set "TASKS=:engine:compileKotlin"
) else if /i "%TARGET%"=="cli" (
    set "TASKS=:app-cli:compileKotlin"
) else if /i "%TARGET%"=="gui" (
    set "TASKS=:app-gui:compileKotlin"
) else if /i "%TARGET%"=="web" (
    set "TASKS=:app-web:compileKotlin"
) else (
    echo Unknown target "%TARGET%". Choose one of: engine cli gui web all 1>&2
    exit /b 1
)

echo Compiling InstaGene (!TARGET!)...
call gradlew.bat !TASKS! --console=plain --quiet !REST!
if errorlevel 1 exit /b %errorlevel%
echo Compilation successful.