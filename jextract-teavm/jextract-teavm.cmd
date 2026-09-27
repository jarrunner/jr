@echo off
rem Runs the generator ON jextract's own runtime image, which carries the org.openjdk.jextract module,
rem libclang.dll and clang's builtin headers. JEXTRACT_HOME defaults to where README.md says to unpack it.
if "%JEXTRACT_HOME%"=="" set "JEXTRACT_HOME=C:\user\Apps\jextract\jextract-25"
"%JEXTRACT_HOME%\runtime\bin\java" --enable-native-access=org.openjdk.jextract --add-modules org.openjdk.jextract ^
  --add-exports org.openjdk.jextract/org.openjdk.jextract.impl=ALL-UNNAMED --add-opens org.openjdk.jextract/org.openjdk.jextract.impl=ALL-UNNAMED ^
  -jar "%~dp0target\jextract-teavm.jar" %*
