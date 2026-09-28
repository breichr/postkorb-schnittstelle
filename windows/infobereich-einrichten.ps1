<#
.SYNOPSIS
  Richtet das Postkorb-Programm im Infobereich (neben der Uhr) ein:
  Verknüpfung im Autostart und im Startmenü, Zertifikats-Passwort, Start.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\infobereich-einrichten.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\infobereich-einrichten.ps1 -Entfernen
#>
param(
    [string]$Pfad = $PSScriptRoot,
    [switch]$Entfernen
)

$ErrorActionPreference = "Stop"
$name = "USP Postkorb"
$autostart = Join-Path ([Environment]::GetFolderPath("Startup")) "$name.lnk"
$startmenue = Join-Path ([Environment]::GetFolderPath("Programs")) "$name.lnk"

if ($Entfernen) {
    Remove-Item $autostart, $startmenue -ErrorAction SilentlyContinue
    Write-Host "Verknüpfungen entfernt. Ein laufendes Programm über Rechtsklick auf das Symbol > Beenden schließen."
    return
}

$javaw = Join-Path $Pfad "runtime\bin\javaw.exe"
if (-not (Test-Path $javaw)) {
    throw "Java fehlt in $Pfad\runtime – zuerst .\java-einrichten.ps1 ausführen."
}
$jar = Join-Path $Pfad "postkorb-schnittstelle.jar"
$config = Join-Path $Pfad "config\postkorb.properties"
foreach ($f in $jar, $config) {
    if (-not (Test-Path $f)) { throw "Nicht gefunden: $f" }
}

# Zertifikats-Passwort dauerhaft für diesen Benutzer hinterlegen (falls noch nicht geschehen)
if (-not [Environment]::GetEnvironmentVariable("POSTKORB_KEYSTORE_PASSWORD", "User")) {
    $sec = Read-Host "Passwort des USP-Zertifikats (.p12)" -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    [Environment]::SetEnvironmentVariable("POSTKORB_KEYSTORE_PASSWORD", $plain, "User")
    $env:POSTKORB_KEYSTORE_PASSWORD = $plain
    Write-Host "Passwort für Benutzer $env:USERNAME gespeichert."
}

# ELAK-Passwort (nur wenn in der Konfiguration ein ELAK-Benutzer eingetragen ist)
if ((Select-String -Path $config -Pattern '^\s*elak\.benutzer\s*=\s*\S' -Quiet) -and
    -not [Environment]::GetEnvironmentVariable("POSTKORB_ELAK_PASSWORD", "User")) {
    $sec = Read-Host "Passwort für den ELAK (wie im Outlook-Add-In)" -AsSecureString
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    [Environment]::SetEnvironmentVariable("POSTKORB_ELAK_PASSWORD", $plain, "User")
    $env:POSTKORB_ELAK_PASSWORD = $plain
    Write-Host "ELAK-Passwort für Benutzer $env:USERNAME gespeichert."
}

$shell = New-Object -ComObject WScript.Shell
foreach ($lnk in $autostart, $startmenue) {
    $s = $shell.CreateShortcut($lnk)
    $s.TargetPath = $javaw
    $s.Arguments = "-jar `"$jar`" --config `"$config`" --tray"
    $s.WorkingDirectory = $Pfad
    $s.Description = "Automatische Abholung aus USP Mein Postkorb"
    $s.Save()
}
Write-Host "Verknüpfung im Autostart und im Startmenü angelegt ($name)."

if (Get-ScheduledTask -TaskName "USP Postkorb Abholung" -ErrorAction SilentlyContinue) {
    Write-Warning "Es gibt zusätzlich die geplante Aufgabe 'USP Postkorb Abholung'. Sie ist nicht mehr nötig:"
    Write-Warning "  Unregister-ScheduledTask -TaskName 'USP Postkorb Abholung' -Confirm:`$false"
}

Start-Process -FilePath $javaw -ArgumentList "-jar `"$jar`" --config `"$config`" --tray" -WorkingDirectory $Pfad
Write-Host "Gestartet – das Symbol erscheint neben der Uhr (ggf. unter dem Pfeil ^ für ausgeblendete Symbole)."
