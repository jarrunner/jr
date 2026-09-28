@echo off
REM Comprehensive test script for jr.exe
cd ..

echo ========================================
echo Testing jr.exe - Java Runner
echo ========================================
echo.

echo Test 1: Display help (no arguments)
echo ----------------------------------------
jr.exe
echo.
pause

echo Test 2: Traditional mode - simple JAR launch
echo ----------------------------------------
jr.exe test-scripts\TestStartupTiming.jar
echo.
pause

echo Test 3: Create config file
echo ----------------------------------------
jr.exe -Xjr:create-config=test-scripts\TestStartupTiming.jar
echo.
pause

echo Test 4: View created config
echo ----------------------------------------
type jr.jrc
echo.
pause

echo Test 5: Test config-based mode (copy and rename)
echo ----------------------------------------
copy jr.exe mytest.exe
copy test-scripts\TestStartupTiming.jar mytest.jar
echo Creating mytest.jrc...
(
echo # Test configuration
echo vm.args=-Xmx256m
echo java.args=-jar mytest.jar
echo app.args=--message "Hello from config mode"
echo aot=true
echo log.file=mytest.log
echo log.level=info
) > mytest.jrc

echo Running mytest.exe...
mytest.exe
echo.
pause

echo Test 6: View log file
echo ----------------------------------------
if exist mytest.log (
    type mytest.log
) else (
    echo Log file not created
)
echo.
pause

echo Test 7: Test with -Xjr:aot=false flag
echo ----------------------------------------
jr.exe -Xjr:aot=false test-scripts\TestStartupTiming.jar
echo.
pause

echo Test 8: Test with custom Java home (if available)
echo ----------------------------------------
REM Uncomment if you have multiple JDKs
REM jr.exe -Xjr:java.home=C:\Java\jdk-21 test-scripts\TestStartupTiming.jar
echo Skipped (uncomment in script if needed)
echo.

echo ========================================
echo All tests completed!
echo ========================================
pause
