@echo off
rem Regenerates pocapp.jr.WinApi and pocapp.jr.WinOffsets from winapi.symbols against the real mingw headers
rem (jr.h lists them), then has llvm-mingw's clang re-check every size, offset, width and value the generator
rem wrote (verify-win.c). One command, run from anywhere: bindings\gen-bindings.cmd
rem Needs ..\..\jextract-teavm built once (see its README). JEXTRACT_TEAVM and LLVM_MINGW override where they are.
setlocal
if "%JEXTRACT_TEAVM%"=="" set "JEXTRACT_TEAVM=%~dp0..\..\jextract-teavm\jextract-teavm.cmd"
if "%LLVM_MINGW%"=="" set "LLVM_MINGW=C:\user\Apps\cmdtools\llvm-mingw-msvcrt-x86_64"
rem Run from this folder so the generated javadoc names jr.h, not an absolute path on this machine.
pushd "%~dp0"
call "%JEXTRACT_TEAVM%" jr.h --symbols winapi.symbols ^
  -I "%LLVM_MINGW%\include" --package pocapp.jr --api-class WinApi --structs-class WinOffsets ^
  -o ..\src\main\java --verify-c verify-win.c
if errorlevel 1 (echo gen-bindings: generation FAILED, see NOT BOUND lines above & popd & exit /b 1)
"%LLVM_MINGW%\bin\x86_64-w64-mingw32-clang" -fsyntax-only -w verify-win.c
if errorlevel 1 (echo gen-bindings: llvm-mingw clang DISAGREES with the generated bindings, see above & popd & exit /b 1)
popd
echo gen-bindings: OK, verified by llvm-mingw clang
