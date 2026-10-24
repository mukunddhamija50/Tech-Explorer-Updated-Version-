@echo off
setlocal
cd /d "%~dp0"
set "SKILLMATCH_JAVA=java.exe"
if defined SKILLMATCH_JAVA_HOME if exist "%SKILLMATCH_JAVA_HOME%\bin\java.exe" set "SKILLMATCH_JAVA=%SKILLMATCH_JAVA_HOME%\bin\java.exe"
if not defined SKILLMATCH_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "SKILLMATCH_JAVA=%JAVA_HOME%\bin\java.exe"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0SetupAuthDependencies.ps1"
if errorlevel 1 (
  echo Could not install the SQLite runtime dependencies. >> "%~dp0skillmatch.log"
  exit /b 1
)
if not defined SKILLMATCH_DEV_OTP set "SKILLMATCH_DEV_OTP=true"
"%SKILLMATCH_JAVA%" --enable-native-access=ALL-UNNAMED -cp "%~dp0.;%~dp0lib\*" index >> "%~dp0skillmatch.log" 2>&1
if errorlevel 1 echo Java server exited with error code %errorlevel%. >> "%~dp0skillmatch.log"
