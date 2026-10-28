@echo off
rem RAS console launcher. Needs JDK/JRE 17+ with JavaFX (Axiom JDK Full, Liberica Full).
setlocal
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA_EXE (
  for /d %%D in ("C:\Program Files\1C\1CE\components\axiom-jdk-full-*") do set "JAVA_EXE=%%D\bin\javaw.exe"
)
if not defined JAVA_EXE set "JAVA_EXE=javaw.exe"
rem Extra JVM options, e.g. set RAS_JAVA_OPTS=-Xmx512m
start "" "%JAVA_EXE%" --enable-native-access=javafx.graphics %RAS_JAVA_OPTS% -jar "%~dp0ras-console.jar" %*
