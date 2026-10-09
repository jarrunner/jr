@echo off
rem Regenerates jarrunner.jr.WinApi and jarrunner.jr.WinOffsets from winapi.symbols against the real mingw
rem headers (jr-winapi.h lists them), then has llvm-mingw's clang re-check every size, offset, width and value the
rem generator wrote (verify-win.c). One command, run from anywhere: bindings\windows\gen-bindings.cmd
rem Needs the jextract-teavm image: the jextract fork (github.com/jarrunner/jextract, branch teavm, see teavm/README.md)
rem checked out beside jr and built with gradlew createJextractTeavmImage, or a release image (set JEXTRACT_TEAVM).
rem JEXTRACT_TEAVM and LLVM_MINGW override where they are.
setlocal
if "%JEXTRACT_TEAVM%"=="" set "JEXTRACT_TEAVM=%~dp0..\..\..\jextract\build\jextract-teavm\bin\jextract-teavm.bat"
if "%LLVM_MINGW%"=="" set "LLVM_MINGW=C:\user\Apps\cmdtools\llvm-mingw-msvcrt-x86_64"
rem Run from this folder so the generated javadoc names jr-winapi.h, not an absolute path on this machine.
pushd "%~dp0"
call "%JEXTRACT_TEAVM%" jr-winapi.h --symbols winapi.symbols --include jr-winapi.h ^
  -I "%LLVM_MINGW%\include" --package jarrunner.jr --api-class WinApi --structs-class WinOffsets --ctype-annotation CType ^
  --text-converter char=N.utf8 --text-converter wchar_t=N.wcstr --text-scope N.mark,N.release --buf-class Buf --returned-annotation Returned --escapes-annotation Escapes --acquires-annotation Acquires --fails-annotation Fails ^
  -o ..\..\src\main\java --verify-c verify-win.c
if errorlevel 1 (echo gen-bindings: generation FAILED, see NOT BOUND lines above & popd & exit /b 1)
"%LLVM_MINGW%\bin\x86_64-w64-mingw32-clang" -fsyntax-only -w verify-win.c
if errorlevel 1 (echo gen-bindings: llvm-mingw clang DISAGREES with the generated bindings, see above & popd & exit /b 1)
popd
echo gen-bindings: OK, verified by llvm-mingw clang
