@echo off
rem Startet das Programm im Infobereich ohne Konsolenfenster.
setlocal
set "BASE=%~dp0"
if exist "%BASE%runtime\bin\javaw.exe" (
  set "JAVAW=%BASE%runtime\bin\javaw.exe"
) else (
  set "JAVAW=javaw"
)
start "" "%JAVAW%" -jar "%BASE%postkorb-schnittstelle.jar" --config "%BASE%config\postkorb.properties" --tray
