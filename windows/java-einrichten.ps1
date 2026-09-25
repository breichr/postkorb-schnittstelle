<#
.SYNOPSIS
  Lädt eine Java-21-Laufzeit (Eclipse Temurin JRE) nach .\runtime.
  postkorb.cmd verwendet sie automatisch; ein installiertes Java (z. B. Java 8) bleibt unverändert.
  Keine Administratorrechte nötig.
#>
param([string]$Pfad = $PSScriptRoot)

$ErrorActionPreference = "Stop"
$runtime = Join-Path $Pfad "runtime"
if (Test-Path (Join-Path $runtime "bin\java.exe")) {
    Write-Host "Java ist bereits in $runtime vorhanden:"
    & (Join-Path $runtime "bin\java.exe") -version
    return
}

$zip = Join-Path $env:TEMP "postkorb-jre.zip"
$tmp = Join-Path $env:TEMP "postkorb-jre"
Write-Host "Lade Java 21 (Eclipse Temurin JRE) ..."
Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk" -OutFile $zip
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive $zip -DestinationPath $tmp
Move-Item (Get-ChildItem -Directory $tmp | Select-Object -First 1).FullName $runtime
Remove-Item $zip, $tmp -Recurse -Force

& (Join-Path $runtime "bin\java.exe") -version
Write-Host "Fertig. postkorb.cmd verwendet jetzt $runtime."
