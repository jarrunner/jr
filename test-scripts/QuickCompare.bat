@echo off
REM Quick side-by-side AOT comparison
cd..

echo.
echo ================================================================================
echo QUICK AOT COMPARISON TEST
echo ================================================================================
echo.
echo This script runs the same JAR twice:
echo   1. WITHOUT AOT (baseline)
echo   2. WITH AOT (using cache)
echo.
echo Look for the "GRAND TOTAL" line in each output to compare.
echo.
pause

REM Clean old AOT cache to start fresh
del /Q test-scripts\GeneralAndAOTPerformanceTest.*.aot 2>nul

echo.
echo ================================================================================
echo TEST 1: WITHOUT AOT (Disabled)
echo ================================================================================
echo.
jarrunner.exe -Xjr:aot=false test-scripts\GeneralAndAOTPerformanceTest.jar | findstr /C:"AOT Status:" /C:"Enabled:" /C:"Mode:" /C:"GRAND TOTAL" /C:"Library loading:" /C:"Guava" /C:"Commons" /C:"Jackson"

echo.
echo.
echo ================================================================================
echo TEST 2: WITH AOT - First Run (Creating Cache)
echo ================================================================================
echo (AOT is enabled by default - this creates the cache)
echo.
jarrunner.exe test-scripts\GeneralAndAOTPerformanceTest.jar >nul 2>&1

echo Cache created! Running again to use the cache...
echo.
echo.
echo ================================================================================
echo TEST 3: WITH AOT - Second Run (Using Cache) *** THIS IS THE FAST ONE ***
echo ================================================================================
echo.
jarrunner.exe test-scripts\GeneralAndAOTPerformanceTest.jar | findstr /C:"AOT Status:" /C:"Enabled:" /C:"Mode:" /C:"GRAND TOTAL" /C:"Library loading:" /C:"Guava" /C:"Commons" /C:"Jackson"

echo.
echo.
echo ================================================================================
echo COMPARISON COMPLETE
echo ================================================================================
echo.
echo Compare the "GRAND TOTAL" times:
echo   WITHOUT AOT: Should be around 990-1000 ms (~1 second)
echo   WITH AOT:    Should be around 80-100 ms (~0.08 seconds)
echo.
echo That's approximately 12x FASTER with AOT!
echo.
pause
