<#
.SYNOPSIS
  Richtet in der Windows-Aufgabenplanung eine regelmäßige Postkorb-Abholung ein.
.EXAMPLE
  .\aufgabe-einrichten.ps1 -Pfad C:\Postkorb -IntervallMinuten 60
#>
param(
    [string]$Pfad = $PSScriptRoot,
    [int]$IntervallMinuten = 60,
    [string]$Name = "USP Postkorb Abholung"
)

$cmd = Join-Path $Pfad "postkorb.cmd"
if (-not (Test-Path $cmd)) { throw "postkorb.cmd nicht gefunden in $Pfad" }

$action  = New-ScheduledTaskAction -Execute $cmd -Argument "--once" -WorkingDirectory $Pfad
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
           -RepetitionInterval (New-TimeSpan -Minutes $IntervallMinuten)
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
           -ExecutionTimeLimit (New-TimeSpan -Hours 1)

# Läuft unter dem aktuellen Benutzer, auch wenn dieser nicht angemeldet ist
# (nötig für tls.keystore.type=Windows-MY, da der Zertifikatsspeicher benutzerbezogen ist).
$cred = Get-Credential -UserName "$env:USERDOMAIN\$env:USERNAME" -Message "Konto, unter dem die Abholung läuft"
Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger -Settings $settings `
    -User $cred.UserName -Password $cred.GetNetworkCredential().Password -RunLevel Limited -Force | Out-Null

Write-Host "Aufgabe '$Name' eingerichtet (alle $IntervallMinuten Minuten)."
