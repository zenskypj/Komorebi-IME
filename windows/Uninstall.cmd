@echo off
setlocal
if not exist "%~dp0Setup.exe" (
  echo Extract the entire ZIP before uninstalling Komorebi Desktop.
  pause
  exit /b 1
)
start "" /wait "%~dp0Setup.exe" /uninstall
set "result=%errorlevel%"
if not "%result%"=="0" (
  echo Setup did not complete. Exit code: %result%
  pause
)
exit /b %result%
