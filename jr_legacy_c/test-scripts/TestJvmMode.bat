@echo off
REM Non-interactive test for PRP-05: jvm=dll (in-process JVM) vs jvm=exe (java.exe child).
REM Run from a real terminal. Each check prints PASS or FAIL.

setlocal enabledelayedexpansion
cd /d "%~dp0.."

set WORK=%TEMP%\jr-jvmmode-test
set JAR=%WORK%\JvmModeTest.jar
set FAILED=0

if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%\classes" 2>nul

echo ========================================
echo Building test JAR
echo ========================================
javac -d "%WORK%\classes" test-scripts\JvmModeTest.java || goto :nojava
jar --create --file "%JAR%" --main-class JvmModeTest -C "%WORK%\classes" . || goto :nojava
echo Built %JAR%
echo.

echo ========================================
echo 1. Default (no flags) must still use java.exe
echo ========================================
jr.exe -Xjr:aot=false "%JAR%" --report=%WORK%\r1.txt >nul 2>&1
call :expect r1.txt "process" "java.exe" "default launch mode is java.exe"

echo ========================================
echo 2. -Xjr:jvm=dll runs the JVM inside jr.exe
echo ========================================
jr.exe -Xjr:jvm=dll -Xjr:aot=false "%JAR%" --report=%WORK%\r2.txt >nul 2>&1
call :expect r2.txt "process" "jr.exe" "in-process JVM keeps the launcher name"

echo ========================================
echo 3. A renamed exe with jvm=dll runs under its own name
echo ========================================
copy /y jr.exe "%WORK%\renamed.exe" >nul
(
echo java.args=-jar %JAR%
echo app.args=--report=%WORK%\r3.txt
echo aot=false
echo jvm=dll
) > "%WORK%\renamed.jrc"
"%WORK%\renamed.exe" >nul 2>&1
call :expect r3.txt "process" "renamed.exe" "config mode honours jvm=dll"

echo ========================================
echo 4. jvm=exe in the config keeps the old behaviour
echo ========================================
copy /y jr.exe "%WORK%\childmode.exe" >nul
(
echo java.args=-jar %JAR%
echo app.args=--report=%WORK%\r4.txt
echo aot=false
echo jvm=exe
) > "%WORK%\childmode.jrc"
"%WORK%\childmode.exe" >nul 2>&1
call :expect r4.txt "process" "java.exe" "config mode honours jvm=exe"

echo ========================================
echo 5. Exit codes propagate in both modes
echo ========================================
jr.exe -Xjr:jvm=dll -Xjr:aot=false "%JAR%" --exit=7 >nul 2>&1
call :expectcode 7 "exit code, in-process mode"
jr.exe -Xjr:jvm=exe -Xjr:aot=false "%JAR%" --exit=7 >nul 2>&1
call :expectcode 7 "exit code, child process mode"

echo ========================================
echo 6. Console output reaches the console in both modes
echo ========================================
jr.exe -Xjr:jvm=dll -Xjr:aot=false "%JAR%" --report=%WORK%\r6.txt >nul 2>&1
call :expect r6.txt "stdout.ok" "true" "stdout writes succeed, in-process mode"
jr.exe -Xjr:jvm=exe -Xjr:aot=false "%JAR%" --report=%WORK%\r7.txt >nul 2>&1
call :expect r7.txt "stdout.ok" "true" "stdout writes succeed, child process mode"

echo ========================================
echo 7. Redirection is preserved in both modes
echo ========================================
jr.exe -Xjr:jvm=dll -Xjr:aot=false "%JAR%" > "%WORK%\r8.out" 2>&1
call :expectfile r8.out "done" "redirected stdout, in-process mode"
jr.exe -Xjr:jvm=exe -Xjr:aot=false "%JAR%" > "%WORK%\r9.out" 2>&1
call :expectfile r9.out "done" "redirected stdout, child process mode"

echo ========================================
echo 8. Piped stdin is readable in both modes
echo ========================================
echo piped-value | jr.exe -Xjr:jvm=dll -Xjr:aot=false "%JAR%" --report=%WORK%\r10.txt --stdin >nul 2>&1
call :expect r10.txt "stdin" "piped-value" "stdin, in-process mode"
echo piped-value | jr.exe -Xjr:jvm=exe -Xjr:aot=false "%JAR%" --report=%WORK%\r11.txt --stdin >nul 2>&1
call :expect r11.txt "stdin" "piped-value" "stdin, child process mode"

echo ========================================
echo 9. Quoted arguments survive intact
echo ========================================
jr.exe -Xjr:jvm=dll -Xjr:aot=false "%JAR%" --report=%WORK%\r12.txt "spaced arg here" >nul 2>&1
call :expect r12.txt "arg[1]" "spaced arg here" "quoted argument, in-process mode"

echo ========================================
echo 10. AOT cache is created and then reused, in-process
echo ========================================
copy /y "%JAR%" "%WORK%\aotcopy.jar" >nul
jr.exe -Xjr:jvm=dll -Xjr:aot=true "%WORK%\aotcopy.jar" --report=%WORK%\r13.txt >nul 2>&1
if exist "%WORK%\aotcopy.*.aot" (echo PASS - AOT cache created) else (echo FAIL - AOT cache created & set FAILED=1)
jr.exe -Xjr:jvm=dll -Xjr:aot=true "%WORK%\aotcopy.jar" --report=%WORK%\r14.txt >nul 2>&1
call :expect r14.txt "process" "jr.exe" "AOT cache reuse, in-process mode"

echo.
echo ========================================
if "%FAILED%"=="0" (echo ALL CHECKS PASSED) else (echo SOME CHECKS FAILED)
echo ========================================
echo Working files: %WORK%
exit /b %FAILED%

:expect
REM %1=report file  %2=key  %3=expected substring  %4=description
if not exist "%WORK%\%~1" (
    echo FAIL - %~4 ^(no report file^)
    set FAILED=1
    goto :eof
)
findstr /c:"%~2" "%WORK%\%~1" | findstr /c:"%~3" >nul
if errorlevel 1 (
    echo FAIL - %~4
    set FAILED=1
) else (
    echo PASS - %~4
)
goto :eof

:expectcode
REM %1=expected exit code  %2=description
if "%ERRORLEVEL%"=="%~1" (echo PASS - %~2) else (echo FAIL - %~2 ^(got %ERRORLEVEL%^) & set FAILED=1)
goto :eof

:expectfile
REM %1=output file  %2=expected substring  %3=description
findstr /c:"%~2" "%WORK%\%~1" >nul 2>&1
if errorlevel 1 (echo FAIL - %~3 & set FAILED=1) else (echo PASS - %~3)
goto :eof

:nojava
echo.
echo Could not build the test JAR. Make sure javac and jar are on PATH.
exit /b 1
