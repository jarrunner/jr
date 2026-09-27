@echo off
REM Build script for Microsoft Visual C++ compiler
REM This script builds jr.exe (Java Runner) using MSVC

REM We are assuming that there is batch file called devcmd.bat which if run, will initialize this cmd's environment variables to support building using MSVC Build tools.
REM MS VC build tools are installable portably, this is far easier from downloading the full massive bundle, which takes space, wastes time, requires admin access (which not everyone always has).
REM I used this - https://github.com/Data-Oriented-House/PortableBuildTools (the repo is now archived ... but it works for me)
REM
REM Only call it if the environment is not already usable. devcmd.bat overwrites
REM INCLUDE and LIB wholesale, so calling it on top of a working Developer Command
REM Prompt (or a devcmd.bat whose install has since moved) replaces good paths with
REM dead ones and the build fails at windows.h. Probe first, set up only if needed.
where cl.exe >nul 2>&1
if %ERRORLEVEL% EQU 0 if defined INCLUDE if defined LIB (
    echo MSVC environment already set, using it as-is.
    goto :envready
)

echo Setting up MSVC environment...
call devcmd.bat >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Warning: devcmd.bat not found in PATH, assuming MSVC env is already set
    echo.
)

:envready

echo Building jr.exe (hybrid CRT) with MSVC...
echo.

REM Hybrid CRT: small AND standalone - no VC++ Redistributable required.
REM
REM /MT alone links the STATIC UCRT, which drags in the whole printf/stdio/locale
REM machinery and costs ~160 KB. /MD instead imports it from the DLLs, but also
REM imports VCRUNTIME140.dll - and that one is NOT part of Windows, so it forces
REM the VC++ Redistributable on every machine.
REM
REM The fix is to split the two halves apart. Compile /MT so vcruntime is linked
REM statically (it is tiny - compiler intrinsics, SEH glue and startup), then tell
REM the linker to drop the static UCRT and bind the SYSTEM one instead:
REM   /NODEFAULTLIB:libucrt.lib  - discard the static UCRT
REM   /DEFAULTLIB:ucrt.lib       - import ucrtbase.dll via api-ms-win-crt-*
REM UCRT has been an OS component since Windows 10 (ucrtbase.dll in System32), so
REM it never needs installing. Result: ~40 KB, zero redistributable.
REM (Same approach Microsoft uses for Windows Terminal.)
REM
REM Requires Windows 10+, or KB2999226 on Windows 7/8.1.
REM
REM Size flags: /O1 (favour size) /GS- (no security cookies) /Gy (COMDAT functions)
REM Link flags: /OPT:REF (drop unused) /OPT:ICF (merge identical) /MERGE:.rdata=.text
REM javainstall.c (the Java auto-install feature, PRP-09) pulls in winhttp.lib,
REM bcrypt.lib and comctl32.lib itself via #pragma comment(lib,...) - all three are
REM OS-provided DLLs (winhttp.dll/bcrypt.dll/comctl32.dll), not redistributable-requiring
REM static libs, so this does not reopen the PRP-06 size/redistributable work.
REM resedit.c (resource editing + signing, PRP-13) likewise pulls in crypt32.lib and
REM version.lib, and loads mssign32.dll at run time - all OS components too.
cl /nologo /O1 /GS- /Gy /MT /Fe:jr.exe launcher.c javainstall.c resedit.c /link /SUBSYSTEM:CONSOLE /NODEFAULTLIB:libucrt.lib /DEFAULTLIB:ucrt.lib /OPT:REF /OPT:ICF /MERGE:.rdata=.text user32.lib kernel32.lib

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ========================================
    echo BUILD FAILED: jr.exe
    echo ========================================
    echo Please ensure Visual Studio/MSVC is installed.
    echo Run from "Developer Command Prompt for VS" or ensure devcmd.bat is in PATH.
    echo.
    exit /b 1
)

REM Clean up intermediate files
if exist launcher.obj del launcher.obj
if exist javainstall.obj del javainstall.obj
if exist resedit.obj del resedit.obj

echo.
echo ========================================
echo BUILD SUCCESSFUL: jr.exe
echo ========================================
dir jr.exe
echo.
echo jr.exe - ~40 KB, no VC++ Redistributable needed (Windows 10+).
echo.
echo Verify the redist dependency is really gone with:
echo     dumpbin /dependents jr.exe
echo Expect USER32, KERNEL32 and api-ms-win-crt-* only.
echo A VCRUNTIME140.dll line means the hybrid link flags did not take effect.
echo.
