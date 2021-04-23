@echo off
setlocal

set ServiceName=session-manager-plugin
set InstallingFolder=%PROGRAMFILES%\Amazon\SessionManagerPlugin
set SsmcliZipFile=%~dp0package.zip

:UNINSTALL
rem Try to remove current installation.
if not exist "%~dp0uninstall.bat" echo [ERROR] uninsall.bat does not exists. & exit /b 1

call "%~dp0uninstall.bat"
if not %errorLevel% == 0 echo [ERROR] Failed when trying to remove current installation. & exit /b 1

:INSTALL
echo [INFO] Detecting administrative permissions...
net session >nul 2>&1
if not %errorLevel% == 0 echo [ERROR] Current permissions are inadequate. & goto exit /b 1
echo [INFO] Administrative permissions confirmed.

echo [INFO] Copy Amazon SessionManagerPlugin from %SsmcliZipFile% to %InstallingFolder%.
call :UnZip "%SsmcliZipFile%" "%InstallingFolder%"

echo [INFO] Register %ServiceName% as Windows service.
sc create %ServiceName% binpath= "%InstallingFolder%\bin\session-manager-plugin.exe" start= auto displayname= "Session Manager Plugin"
if not %errorlevel% == 0 echo [ERROR] Failed to register %ServiceName% as Windows service. & exit /b 1

echo [INFO] Add service description.
sc description %ServiceName% "Session Manager Plugin"
if not %errorlevel% == 0 echo [WARN] Failed to add description for %ServiceName% service.

echo [INFO] Configure %ServiceName% recovery settings.
sc failure %ServiceName% reset= 86400 actions= restart/1000/restart/1000//1000
if not %errorlevel% == 0 echo [WARN] Failed to configure recovery settings for %ServiceName% service.

echo [INFO] Set environment path variable.
echo ;%PATH%; | find /C /I ";%InstallingFolder%\bin\;" >nul
if %errorlevel% == 0 echo "%InstallingFolder%\bin\ already in env:PATH" & goto FINISH
set Empty=0
for /f "skip=2 tokens=3*" %%a in ('reg query HKCU\Environment /v PATH') do if [%%b]==[] ( setx PATH "%%~a;%InstallingFolder%\bin\;" && set Empty=1 ) else ( setx PATH "%%~a %%~b;%InstallingFolder%\bin\;" && set Empty=1 )
if "%Empty%" == "0" setx PATH "%InstallingFolder%\bin\;"

:FINISH
exit /b 0

:UnZip <File> <Destination>
    rem Create destination folder if not exist
    md %2
    rem Create VB script to unzip file
    set vbs="%TEMP%\_.vbs"
    if exist %vbs% del /f /q %vbs%
    >%vbs% echo Set fso = CreateObject("Scripting.FileSystemObject")
    >>%vbs% echo Set objShell = CreateObject("Shell.Application")
    >>%vbs% echo Set FilesInZip=objShell.NameSpace(%1).items
    >>%vbs% echo objShell.NameSpace(%2).CopyHere(FilesInZip)
    >>%vbs% echo Set fso = Nothing
    >>%vbs% echo Set objShell = Nothing
    rem Run VB script
    cscript //nologo %vbs%
    rem Delete VB script
    if exist %vbs% del /f /q %vbs%