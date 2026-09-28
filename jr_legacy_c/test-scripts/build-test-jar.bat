@echo off
REM Build test JAR with dependencies using JBang
REM This creates GeneralAndAOTPerformanceTest.jar for testing jarrunner

cd..

echo Building GeneralAndAOTPerformanceTest.jar with JBang...
echo (This includes Guava, Apache Commons Lang, and Jackson)
echo.

cd test-scripts

REM Clean old artifacts
if exist GeneralAndAOTPerformanceTest.jar del GeneralAndAOTPerformanceTest.jar
if exist lib rmdir /s /q lib 2>nul
if exist .jbang rmdir /s /q .jbang 2>nul

REM Build with JBang
jbang export portable --force -O=GeneralAndAOTPerformanceTest.jar GeneralAndAOTPerformanceTest.java

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo TEST JAR BUILD SUCCESSFUL!
    echo ========================================
    echo Output: test-scripts\GeneralAndAOTPerformanceTest.jar
    echo.
    dir GeneralAndAOTPerformanceTest.jar
    echo.
) else (
    echo.
    echo ========================================
    echo BUILD FAILED!
    echo ========================================
    echo Make sure JBang is installed and in PATH.
    echo.
    exit /b 1
)

cd..
