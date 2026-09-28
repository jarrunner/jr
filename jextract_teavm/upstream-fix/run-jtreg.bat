@echo off
rem Runs jextract's own jtreg suite on whatever is checked out in the fork, on Windows:
rem portable MSVC Build Tools (vsbt) for the native test libraries, via cmake's NMake generator;
rem Gradle 8.11 on JDK 21 because it cannot run on 25, while jextract itself is built with JDK 25.
rem The vsbt environment is set inline: CALLing its devcmd.bat (LF line endings) derails this script.
set "VSBT=C:\user\Apps\vsbt"
set "WindowsSDKDir=%VSBT%\Windows Kits\10"
set "VCToolsInstallDir=%VSBT%\VC\Tools\MSVC\14.44.35207"
set "SDKV=10.0.26100.0"
set "INCLUDE=%VCToolsInstallDir%\include;%WindowsSDKDir%\Include\%SDKV%\ucrt;%WindowsSDKDir%\Include\%SDKV%\shared;%WindowsSDKDir%\Include\%SDKV%\um"
set "LIB=%VCToolsInstallDir%\lib\x64;%WindowsSDKDir%\Lib\%SDKV%\ucrt\x64;%WindowsSDKDir%\Lib\%SDKV%\um\x64"
set "PATH=%VCToolsInstallDir%\bin\Hostx64\x64;%WindowsSDKDir%\bin\%SDKV%\x64;C:\user\Apps\cmake-3.31.8-windows-x86_64\bin;%PATH%"
set "CMAKE_GENERATOR=NMake Makefiles"
set "JAVA_HOME=C:\user\Apps\jdk\jdk-21"
set "SRC=%~1"
if "%SRC%"=="" set "SRC=C:\user\github-mirror\openjdk\jextract"
cd /d "%SRC%"
rem .\ is required: agent shells set NoDefaultCurrentDirectoryInExePath, so a bare name is not found.
call .\gradlew.bat -Pjdk_home=C:\user\Apps\jdk\jdk-25 -Pllvm_home=C:\user\Apps\jextract\llvm-home -Pjtreg_home=C:\user\Apps\jtreg jtreg
