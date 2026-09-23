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
if /i "%TARGET%"=="all" goto :all
if /i "%TARGET%"=="engine" goto :engine
if /i "%TARGET%"=="cli" goto :cli
if /i "%TARGET%"=="gui" goto :gui
if /i "%TARGET%"=="web" goto :web
echo Unknown target "%TARGET%". Choose one of: engine cli gui web all 1>&2
exit /b 1

:engine
set FILTER=--tests "org.instagene.core.*"
goto :run

:cli
set FILTER=--tests "org.instagene.app.cli.*"
goto :run

:gui
set FILTER=--tests "org.instagene.app.gui.*"
goto :run

:web
set FILTER=--tests "org.instagene.app.web.*"
goto :run

:all
set "FILTER="
goto :run

:run
echo Running InstaGene tests (!TARGET!)...
call gradlew.bat :tests:test %FILTER% --console=plain --quiet !REST!
if errorlevel 1 exit /b %errorlevel%
echo Tests passed.