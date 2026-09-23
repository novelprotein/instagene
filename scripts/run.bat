@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=gui"

set "REST="
:collect
shift
if "%~1"=="" goto :parsed
set "REST=!REST! %1"
goto :collect

:parsed
if /i "%TARGET%"=="gui" (
    set "TASK=:app-gui:runGui"
) else if /i "%TARGET%"=="cli" (
    set "TASK=:app-cli:runCli"
) else if /i "%TARGET%"=="web" (
    set "TASK=:app-web:runWeb"
) else (
    echo Unknown run target "%TARGET%". The engine is a library and has no run task. 1>&2
    echo Choose one of: gui cli web 1>&2
    exit /b 1
)

echo Starting InstaGene (!TARGET!)...
call gradlew.bat !TASK! --console=plain --quiet !REST!
if errorlevel 1 exit /b %errorlevel%