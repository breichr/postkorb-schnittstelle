@echo off
rem Startet einen Abholdurchlauf. Weitere Argumente werden durchgereicht (z. B. --check-tls, --loop).
setlocal
set "BASE=%~dp0"
if exist "%BASE%runtime\bin\java.exe" (
  set "JAVA=%BASE%runtime\bin\java.exe"
) else (
  set "JAVA=java"
)
"%JAVA%" -jar "%BASE%postkorb-schnittstelle.jar" --config "%BASE%config\postkorb.properties" %*
exit /b %ERRORLEVEL%
