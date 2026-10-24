@echo off
setlocal
cd /d "%~dp0"

set "SKILLMATCH_JAVA_HOME="
if exist "%USERPROFILE%\.jdk\25\bin\javac.exe" set "SKILLMATCH_JAVA_HOME=%USERPROFILE%\.jdk\25"
if not defined SKILLMATCH_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "SKILLMATCH_JAVA_HOME=%JAVA_HOME%"
if defined SKILLMATCH_JAVA_HOME (
  set "SKILLMATCH_JAVAC=%SKILLMATCH_JAVA_HOME%\bin\javac.exe"
) else (
  set "SKILLMATCH_JAVAC=javac.exe"
)

"%SKILLMATCH_JAVAC%" -version >nul 2>&1
if errorlevel 1 (
  echo Java JDK was not found. Install a JDK and run this file again.
  pause
  exit /b 1
)

if not exist "%~dp0index.java" (
  echo Could not find index.java in:
  echo %~dp0
  pause
  exit /b 1
)
if not exist "%~dp0AppData.java" (
  echo Could not find AppData.java in:
  echo %~dp0
  pause
  exit /b 1
)
if not exist "%~dp0InterviewQuestionBank.java" (
  echo Could not find InterviewQuestionBank.java in:
  echo %~dp0
  pause
  exit /b 1
)
if not exist "%~dp0index.html" (
  echo Could not find index.html in:
  echo %~dp0
  pause
  exit /b 1
)

echo Compiling SkillMatch Java server...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0SetupAuthDependencies.ps1"
if errorlevel 1 (
  echo Required SQLite authentication libraries could not be installed.
  pause
  exit /b 1
)

"%SKILLMATCH_JAVAC%" --release 25 -encoding UTF-8 -cp "%~dp0lib\*" -d "%~dp0." "%~dp0index.java" "%~dp0AppData.java" "%~dp0AuthStore.java" "%~dp0InterviewQuestionBank.java"
if errorlevel 1 (
  echo Java compilation failed. This project requires a JDK that supports --release 25.
  echo Install JDK 25 or newer, then run this file again.
  pause
  exit /b 1
)

set "LOCAL_BUILD="
for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$item = Get-Item -LiteralPath '%~dp0index.class'; [Console]::Write(([DateTimeOffset]$item.LastWriteTimeUtc).ToUnixTimeMilliseconds())"') do set "LOCAL_BUILD=%%I"
set "RUNNING_BUILD="
for /f "delims=" %%I in ('powershell.exe -NoProfile -Command "$runtime = & curl.exe --silent --fail --max-time 2 'http://127.0.0.1:8000/api/runtime' ^| ConvertFrom-Json; if ($runtime.build_timestamp) { [Console]::Write($runtime.build_timestamp) }"') do set "RUNNING_BUILD=%%I"
if not "%RUNNING_BUILD%"=="" (
  if "%RUNNING_BUILD%"=="%LOCAL_BUILD%" goto skillmatch_ready
  echo.
  echo A previous SkillMatch server build is still running on port 8000.
  echo Close its minimized Java server window, then run this file again to use the newly compiled files.
  pause
  exit /b 1
)

curl.exe --silent --fail --max-time 1 --output NUL "http://127.0.0.1:8000/api/runtime" >nul 2>&1
if not errorlevel 1 (
  echo.
  echo A SkillMatch server is already running on port 8000 but does not report its build version.
  echo Close its Java server window, then run this file again to start the current build.
  pause
  exit /b 1
)

echo Starting SkillMatch Java server...
if exist "%~dp0skillmatch.log" del "%~dp0skillmatch.log"
start "SkillMatch Java API" /min "%~dp0RunSkillMatchServer.bat"

echo Waiting for SkillMatch to start...
for /L %%i in (1,1,60) do (
  curl.exe --silent --fail --max-time 2 "http://127.0.0.1:8000/api/runtime" | findstr.exe /C:auth_dev_otp >nul 2>&1
  if not errorlevel 1 goto skillmatch_ready
  timeout /t 1 /nobreak >nul
)

echo.
echo SkillMatch did not start with SQL account support and local OTP signup on port 8000.
echo Check skillmatch.log for the Java startup error.
if exist "%~dp0skillmatch.log" type "%~dp0skillmatch.log"
echo.
echo If another program is using port 8000, close it and run this file again.
pause
exit /b 1

:skillmatch_ready
echo SkillMatch is running with SQL account and local OTP signup support.
curl.exe --silent --fail --max-time 2 --output NUL "http://127.0.0.1:8080/api/skills" >nul 2>&1
if errorlevel 1 echo Note: Tech Explorer API is not responding on port 8080.
start "" "http://127.0.0.1:8000/index.html"
exit /b 0
