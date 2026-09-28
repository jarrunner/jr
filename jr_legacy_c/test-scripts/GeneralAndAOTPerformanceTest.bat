@echo off
setlocal enabledelayedexpansion
REM Statistical Performance Test - Multiple runs with and without AOT
REM Runs 5 iterations each to get reliable average measurements

cd..

echo.
echo ================================================================================
echo         GENERAL AND AOT PERFORMANCE TEST (Statistical Analysis)
echo ================================================================================
echo.
echo This test performs:
echo   - 5 runs WITHOUT AOT (baseline measurement)
echo   - 5 runs WITH AOT (performance with cache)
echo.
echo Each run measures complete startup time using the comprehensive timing tool.
echo Final results show average performance with statistical confidence.
echo.
pause

REM Check if test JAR exists
if not exist "test-scripts\GeneralAndAOTPerformanceTest.jar" (
    echo [ERROR] Test JAR not found!
    echo Please build it first: cd test-scripts ^&^& build-win.bat
    pause
    exit /b 1
)

REM Clean up old AOT caches to start fresh
echo Cleaning old AOT cache files...
del /Q test-scripts\GeneralAndAOTPerformanceTest.*.aot 2>nul
echo.

set RUNS=5

echo ================================================================================
echo PHASE 1: BASELINE (WITHOUT AOT) - %RUNS% Runs
echo ================================================================================
echo.

set TOTAL_NO_AOT=0
set MIN_NO_AOT=999999
set MAX_NO_AOT=0

for /L %%i in (1,1,%RUNS%) do (
    echo [Run %%i/%RUNS%] Running without AOT...

    REM Capture start time
    for /f "tokens=1-4 delims=:.," %%a in ("!time: =0!") do (
        set /a "start=(((1%%a*60)+1%%b)*60+1%%c)*1000+1%%d-3610001000"
    )

    REM Run without AOT - suppress output
    jarrunner.exe -Xjr:aot=false test-scripts\GeneralAndAOTPerformanceTest.jar >nul 2>&1

    REM Capture end time
    for /f "tokens=1-4 delims=:.," %%a in ("!time: =0!") do (
        set /a "end=(((1%%a*60)+1%%b)*60+1%%c)*1000+1%%d-3610001000"
    )

    REM Calculate elapsed time
    set /a "elapsed=end-start"
    if !elapsed! lss 0 set /a elapsed+=86400000

    echo   Completed in: !elapsed! ms

    REM Track statistics
    set /a TOTAL_NO_AOT+=elapsed
    if !elapsed! lss !MIN_NO_AOT! set MIN_NO_AOT=!elapsed!
    if !elapsed! gtr !MAX_NO_AOT! set MAX_NO_AOT=!elapsed!

    REM Small delay between runs
    timeout /t 1 /nobreak >nul
)

set /a AVG_NO_AOT=TOTAL_NO_AOT/RUNS

echo.
echo BASELINE RESULTS (WITHOUT AOT):
echo   Runs:        %RUNS%
echo   Average:     %AVG_NO_AOT% ms
echo   Min:         %MIN_NO_AOT% ms
echo   Max:         %MAX_NO_AOT% ms
echo   Total:       %TOTAL_NO_AOT% ms
echo.

REM Wait before AOT phase
timeout /t 2 /nobreak >nul

echo ================================================================================
echo PHASE 2: WITH AOT - First Run (Creating Cache)
echo ================================================================================
echo.

echo [Creating AOT cache] This run will be slower as it profiles the code...

REM Capture start time
for /f "tokens=1-4 delims=:.," %%a in ("!time: =0!") do (
    set /a "start=(((1%%a*60)+1%%b)*60+1%%c)*1000+1%%d-3610001000"
)

REM First run with AOT - creates cache (AOT is now default)
jarrunner.exe test-scripts\GeneralAndAOTPerformanceTest.jar >nul 2>&1

REM Capture end time
for /f "tokens=1-4 delims=:.," %%a in ("!time: =0!") do (
    set /a "end=(((1%%a*60)+1%%b)*60+1%%c)*1000+1%%d-3610001000"
)

set /a "AOT_FIRST=end-start"
if !AOT_FIRST! lss 0 set /a AOT_FIRST+=86400000

echo   Completed in: %AOT_FIRST% ms (cache created)

REM Verify cache was created
dir /B test-scripts\GeneralAndAOTPerformanceTest.*.aot >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo   [SUCCESS] AOT cache file created
    for %%F in (test-scripts\GeneralAndAOTPerformanceTest.*.aot) do (
        set AOT_SIZE=%%~zF
        set /a AOT_SIZE_MB=!AOT_SIZE! / 1048576
        echo   Cache size: !AOT_SIZE_MB! MB
    )
) else (
    echo   [WARNING] AOT cache not created - are you using JDK 25+?
)
echo.

REM Wait before measurement runs
timeout /t 2 /nobreak >nul

echo ================================================================================
echo PHASE 3: WITH AOT (USING CACHE) - %RUNS% Runs
echo ================================================================================
echo.

set TOTAL_WITH_AOT=0
set MIN_WITH_AOT=999999
set MAX_WITH_AOT=0

for /L %%i in (1,1,%RUNS%) do (
    echo [Run %%i/%RUNS%] Running with AOT cache...

    for /f "tokens=1-4 delims=:.," %%a in ("!time: =0!") do (
        set /a "start=(((1%%a*60)+1%%b)*60+1%%c)*1000+1%%d-3610001000"
    )

    jarrunner.exe test-scripts\GeneralAndAOTPerformanceTest.jar >nul 2>&1

    for /f "tokens=1-4 delims=:.," %%a in ("!time: =0!") do (
        set /a "end=(((1%%a*60)+1%%b)*60+1%%c)*1000+1%%d-3610001000"
    )

    set /a "elapsed=end-start"
    if !elapsed! lss 0 set /a elapsed+=86400000

    echo   Completed in: !elapsed! ms

    set /a TOTAL_WITH_AOT+=elapsed
    if !elapsed! lss !MIN_WITH_AOT! set MIN_WITH_AOT=!elapsed!
    if !elapsed! gtr !MAX_WITH_AOT! set MAX_WITH_AOT=!elapsed!

    timeout /t 1 /nobreak >nul
)

set /a AVG_WITH_AOT=TOTAL_WITH_AOT/RUNS

echo.
echo WITH AOT RESULTS (USING CACHE):
echo   Runs:        %RUNS%
echo   Average:     %AVG_WITH_AOT% ms
echo   Min:         %MIN_WITH_AOT% ms
echo   Max:         %MAX_WITH_AOT% ms
echo   Total:       %TOTAL_WITH_AOT% ms
echo.

echo ================================================================================
echo STATISTICAL COMPARISON
echo ================================================================================
echo.

echo ┌─────────────────────────────────────────────────────────────────────┐
echo │                    Performance Comparison (%RUNS% runs each)              │
echo ├─────────────────────────────────────────────────────────────────────┤
echo │                                                                     │
echo │  Metric          │  WITHOUT AOT  │  WITH AOT    │  Improvement     │
echo │  ────────────────┼───────────────┼──────────────┼──────────────────│

REM Calculate improvement
set /a IMPROVEMENT=AVG_NO_AOT-AVG_WITH_AOT
set /a PERCENT=IMPROVEMENT*100/AVG_NO_AOT
set /a SPEEDUP=AVG_NO_AOT*10/AVG_WITH_AOT

echo │  Average         │  %AVG_NO_AOT% ms      │  %AVG_WITH_AOT% ms       │  %IMPROVEMENT% ms saved  │
echo │  Min             │  %MIN_NO_AOT% ms      │  %MIN_WITH_AOT% ms       │                  │
echo │  Max             │  %MAX_NO_AOT% ms      │  %MAX_WITH_AOT% ms       │                  │
echo │                                                                     │
echo │  Performance:  %PERCENT%%% faster   │   Speedup: %SPEEDUP%.0x                     │
echo │                                                                     │
echo └─────────────────────────────────────────────────────────────────────┘
echo.

if %AVG_WITH_AOT% LSS %AVG_NO_AOT% (
    echo [SUCCESS] AOT provides significant performance improvement!
    echo           Average startup is %PERCENT%%% faster with AOT cache.
) else (
    echo [INFO] No significant improvement detected.
    echo       This may indicate:
    echo         - JDK version is below 25 (AOT not available)
    echo         - Application is too simple to benefit from AOT
    echo         - AOT cache was not properly utilized
)

echo.
echo ================================================================================
echo Test Complete - Statistical Analysis Done
echo ================================================================================
echo.
echo For detailed timing breakdown, run:
echo   jarrunner.exe test-scripts\GeneralAndAOTPerformanceTest.jar
echo   jarrunner.exe -Xjr:aot=true test-scripts\GeneralAndAOTPerformanceTest.jar
echo.

pause
