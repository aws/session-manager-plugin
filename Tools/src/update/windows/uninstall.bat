@echo off
setlocal

set ServiceName=session-manager-plugin
set ProgramFilesAmazonFolder=%PROGRAMFILES%\Amazon
set ProgramFilesSSMCLIFolder=%ProgramFilesAmazonFolder%\SessionManagerPlugin
set CustomizedSeelog=%ProgramFilesSSMCLIFolder%\seelog.xml

:BEGIN
echo [INFO] Detecting administrative permissions...
net session >nul 2>&1
if not %errorlevel% == 0 echo [ERROR] Current permissions are inadequate. & exit /b 1
echo [INFO] Administrative permissions confirmed.

echo [INFO] Looking for %ServiceName% service...
sc query %ServiceName% > nul
if %errorlevel% == 1060 echo [INFO] Service does not exists. & goto DEL_PROGRAMFILES
echo [INFO] Service found.

echo [INFO] Checking service status...
sc query %ServiceName:"=% | find "STOPPED"
if %errorlevel% == 0 echo [INFO] Service is stopped. & goto DEL_SERVICE
echo [INFO] Service is running.

echo [INFO] Stopping service...
net stop %ServiceName%
if not %errorlevel% == 0 echo [ERROR] Failed to stop service. & exit /b 1
echo [INFO] Service is stopped.

:DEL_SERVICE
echo [INFO] Delete from Windows service controller.
sc delete session-manager-plugin
if not %errorlevel% == 0 echo [ERROR] Failed to delete service. & exit /b 1

:DEL_PROGRAMFILES
if not exist "%ProgramFilesSSMCLIFolder%" goto DEL_AMAZON_PROGRAMFILES
echo [INFO] Delete files under %ProgramFilesSSMCLIFolder%.

rem Loop through folders and delete them.
for /f "delims=" %%i in ('dir /b /a:d "%ProgramFilesSSMCLIFolder%\*.*"') do (
  rd /s/q "%ProgramFilesSSMCLIFolder%\%%i"
)

rem Loop through non-folders, keep the customized files.
set HasCustomizedSettings=
for /f "delims=" %%i in ('dir /b /a:-d "%ProgramFilesSSMCLIFolder%\*.*"') do (
  set IsCustomized=
  if /I "%ProgramFilesSSMCLIFolder%\%%i" equ "%CustomizedSeelog%" set IsCustomized=1
  if defined IsCustomized (
    set HasCustomizedSettings=1
    echo [INFO] Keep %ProgramFilesSSMCLIFolder%\%%i.
  ) else (
    del "%ProgramFilesSSMCLIFolder%\%%i"
  )
)

rem If customized files exists, do not delete the folder.
if defined HasCustomizedSettings goto FINISH

echo [INFO] Delete %ProgramFilesSSMCLIFolder%.
rd /s/q "%ProgramFilesSSMCLIFolder%"

:DEL_AMAZON_PROGRAMFILES
if not exist "%ProgramFilesAmazonFolder%" goto FINISH

rem If Amazon folder contains other content, exit
for /f %%i in ('dir /b "%ProgramFilesAmazonFolder%\*.*"') do (
    goto :FINISH
)

echo [INFO] Delete %ProgramFilesAmazonFolder%.
rd /s/q "%ProgramFilesAmazonFolder%"

:FINISH
exit /b 0
