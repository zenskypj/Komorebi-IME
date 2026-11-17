@echo off
setlocal
fltmc >nul 2>&1
if errorlevel 1 (
  echo Open Command Prompt as administrator and run Uninstall.cmd again.
  exit /b 1
)
set "target=%ProgramFiles%\Komorebi Desktop"
if not exist "%target%\KomorebiDesktop.dll" exit /b 0
"%SystemRoot%\System32\regsvr32.exe" /s /u "%target%\KomorebiDesktop.dll" || exit /b 1
del "%target%\KomorebiDesktop.dll"
if exist "%target%\KomorebiDesktop.dll" (
  echo Unregistered. Close applications using the DLL before removing the file.
  exit /b 1
)
echo Uninstalled.
