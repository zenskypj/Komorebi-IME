@echo off
setlocal
if /i not "%PROCESSOR_ARCHITECTURE%"=="AMD64" (
  echo This preview requires an x64 Windows command prompt.
  exit /b 1
)
fltmc >nul 2>&1
if errorlevel 1 (
  echo Open Command Prompt as administrator and run Install.cmd again.
  exit /b 1
)
set "target=%ProgramFiles%\Komorebi Desktop"
if not exist "%~dp0KomorebiDesktop.dll" exit /b 1
if not exist "%target%\" mkdir "%target%" || exit /b 1
copy /y "%~dp0KomorebiDesktop.dll" "%target%\KomorebiDesktop.dll" >nul || exit /b 1
"%SystemRoot%\System32\regsvr32.exe" /s "%target%\KomorebiDesktop.dll"
if errorlevel 1 (
  echo TSF registration failed. Run regsvr32 without /s to see the error.
  exit /b 1
)
echo Registered. Add Komorebi Desktop to Japanese in Windows language settings.
