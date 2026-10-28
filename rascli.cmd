@echo off
rem RAS console command line (for scripts and Windows Task Scheduler). Run "rascli help".
setlocal
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
  for /d %%D in ("C:\Program Files\1C\1CE\components\axiom-jdk-full-*") do set "JAVA_EXE=%%D\bin\java.exe"
)
if not defined JAVA_EXE set "JAVA_EXE=java.exe"
"%JAVA_EXE%" %RAS_JAVA_OPTS% -cp "%~dp0ras-console.jar;%~dp0lib\*" ru.rasconsole.CliBoot %*
exit /b %ERRORLEVEL%
