# postkorb-schnittstelle

Windows-Schnittstelle zum automatisierten Herunterladen von Dokumenten aus
**„Mein Postkorb“ im Unternehmensserviceportal (USP)**.

Die Schnittstelle nutzt die offizielle Funktion **„Automatische Abholung“** des USP:

- SOAP-Webservice unter `https://autoabholung.meinpostkorb.brz.gv.at/soap`
- beidseitig zertifikatsgesicherte Verbindung (Client-Zertifikat aus dem USP)
- jeder Anhang wird über einen eigenen REST-GET-Aufruf geladen (mit derselben TLS-Absicherung)
- Schnittstellenbeschreibung: `zuseaa_p2.wsdl` (SOAP 1.2) samt `.xsd`-Dateien aus dem USP-Beispielprojekt,
  abgelegt unter `src/main/resources/wsdl` und `src/main/resources/zusemsg`
- Serverzertifikat der BRZ-StammCA: `config/brz_ca.cer`

### Ablauf eines Durchlaufs

| Schritt | SOAP-Funktion | Bedeutung |
|---|---|---|
| 1 | `QueryDeliveries` mit `NewDeliveriesOnly` | IDs aller noch nicht abgeschlossenen Zustellungen (max. `query.limit` je Abfrage) |
| 2 | `GetDelivery` | Metadaten (Absender, Betreff, GZ, Zustellqualität …) und Anhangsliste mit Größe und Prüfsumme |
| 3 | REST-GET `/attachment?delivery_id=…&attachment_id=…` | Download je Anhang; Größe und Prüfsumme (SHA256/SHA512) werden geprüft. Anhang 1 ist immer der Nachrichtentext („mailbody“, wird als `mailbody.txt` gespeichert) |
| 4 | `CloseDelivery` | erst nachdem alles gespeichert ist: markiert die Nachricht als gelesen; sie bleibt im USP sichtbar, kommt aber nicht mehr als „neu“ |
| 5 | `DeleteDelivery` | nur mit `delete.after.download=true`: die Nachricht ist danach im USP nicht mehr verfügbar |

Schlägt ein Schritt fehl, wird die Zustellung nicht abgeschlossen und beim nächsten Lauf erneut versucht.
Wurde sie schon gespeichert und nur das Abschließen schlug fehl, wird sie nicht noch einmal heruntergeladen.

> ⚠️ **Rechtlicher Hinweis:** Mit der Abholung über die Schnittstelle gilt die Zustellung als bewirkt.
> Ab diesem Zeitpunkt laufen Fristen, z. B. für Beschwerden oder Zahlungen. Die heruntergeladenen
> Dokumente müssen also verlässlich bei den zuständigen Personen oder im ERP/DMS ankommen.

## Stand

| Teil | Status |
|---|---|
| Konfiguration, Client-Zertifikat (.p12/.pfx oder Windows-Zertifikatsspeicher), mTLS | ✅ |
| Ablage je Zustellung (Windows-taugliche Dateinamen, atomar, Metadaten) | ✅ |
| Prüfsumme jedes Anhangs (SHA-256 usw., hex oder Base64) | ✅ |
| Keine doppelten Downloads; optionales Löschen im Postkorb erst nach vollständigem Speichern | ✅ |
| Logging, Exit-Codes, Aufgabenplanung / Dauerbetrieb | ✅ |
| Demo-Modus ohne Zertifikat | ✅ |
| SOAP-Anbindung (`ZuseAaSoapGateway`), gegen die offizielle XSD getestet | ✅ |
| Download-Adresse und Beispielantwort laut USP-How-To (März 2025) | ✅ |
| Test gegen den USP-Testzugang unter Windows (3 Nachrichten abgeholt, Prüfsummen ok) | ✅ |
| Echtbetrieb | ⏳ |

## Voraussetzungen im USP

1. Ein USP-Konto mit USP-Administrator.
2. Einem Benutzer ist die Rolle **„Postbevollmächtigter“** für „Mein Postkorb“ zugewiesen.
3. Der Postbevollmächtigte aktiviert unter **Mein Postkorb → Einstellungen → Automatische Abholung**
   die automatische Abholung.
4. Der USP-Administrator erzeugt dort das **Client-Zertifikat** und lädt es herunter.
   (WSDL/XSD und BRZ-CA sind bereits in diesem Projekt enthalten.)

Anleitung des USP (Grundlage dieser Implementierung): [How-To Einrichtung der „Automatischen Abholung“](https://www.usp.gv.at/dam/jcr:0909b669-4372-438f-b3fd-c42a37ffc5f4/Mein_Postkorb_AutomatischeAbholung_HowTo.pdf)

## Bauen

Zum Bauen: Java 17 oder neuer (z. B. [Eclipse Temurin](https://adoptium.net/)) und Maven.

```bat
mvn package
```

Das Ergebnis ist `target\postkorb-schnittstelle.jar`, eine lauffähige JAR mit allen Abhängigkeiten.
Die JAXB-Klassen werden beim Bauen aus der WSDL erzeugt (Konfiguration wie im USP-Beispielprojekt).

## Einrichten unter Windows

Beispiel für die Ordnerstruktur:

```
C:\Postkorb\
  postkorb-schnittstelle.jar
  postkorb.cmd                 (aus windows\)
  aufgabe-einrichten.ps1       (aus windows\)
  java-einrichten.ps1          (aus windows\)
  config\postkorb.properties   (aus config\postkorb.properties.example)
  config\brz_ca.cer
  zertifikat\client.p12
  runtime\                     (Java 21, von java-einrichten.ps1 geladen)
```

**Java:** Das Programm braucht Java 17 oder neuer; **Java 8 reicht nicht** (Fehler
`UnsupportedClassVersionError … class file version 61.0`). Ohne ein anderes installiertes Java
anzutasten, lädt `java-einrichten.ps1` eine Java-21-Laufzeit nach `C:\Postkorb\runtime`
(keine Administratorrechte nötig). `postkorb.cmd` verwendet sie automatisch.

```powershell
cd C:\Postkorb
powershell -ExecutionPolicy Bypass -File .\java-einrichten.ps1
```

Alle folgenden Befehle sind für **PowerShell** geschrieben (in der klassischen Eingabeaufforderung
`cmd` entfällt das `.\` und Umgebungsvariablen setzt man mit `set NAME=Wert`).

1. Konfiguration anpassen: `config\postkorb.properties`, insbesondere `tls.keystore.path` (die .p12-Datei
   aus dem USP). Das Zertifikats-Passwort am besten als Umgebungsvariable setzen:
   ```powershell
   $env:POSTKORB_KEYSTORE_PASSWORD = "EuerPasswort"
   ```
   Alternativ importiert man das Zertifikat in den Windows-Zertifikatsspeicher des Benutzers
   und setzt `tls.keystore.type=Windows-MY`.
2. Zertifikat prüfen:
   ```powershell
   .\postkorb.cmd --check-tls
   ```
3. Einmal manuell abholen:
   ```powershell
   .\postkorb.cmd
   ```
4. Regelmäßige Abholung einrichten (PowerShell):
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\aufgabe-einrichten.ps1 -Pfad C:\Postkorb -IntervallMinuten 60
   ```
   Wichtig: `$env:POSTKORB_KEYSTORE_PASSWORD` gilt nur im aktuellen Fenster. Für die geplante
   Aufgabe das Passwort dauerhaft für das Benutzerkonto setzen, unter dem die Aufgabe läuft:
   ```powershell
   [Environment]::SetEnvironmentVariable("POSTKORB_KEYSTORE_PASSWORD", "EuerPasswort", "User")
   ```
   Alternativ läuft `postkorb.cmd --loop` dauerhaft, z. B. als Dienst über NSSM oder WinSW.

### Ablage

```
C:\Postkorb\Eingang\
  2026-09-25_Finanzamt Österreich_<Zustellungs-ID>\
    <Nachrichtentext>     ← Anhang 1 ist immer der Text der Nachricht
    Bescheid.pdf
    zustellung.txt        ← Absender, Betreff, Eingang, Geschäftszahl, Zustellqualität, Anhänge
  .abgeholt.txt           ← bereits abgeholte IDs
  logs\postkorb-0.log
```

Jede Zustellung wird zuerst in einen temporären Ordner geschrieben und erst am Ende umbenannt.
Ein nachgelagertes System, das `Eingang\` überwacht, sieht deshalb nie halbfertige Zustellungen.

### Exit-Codes (`--once`)

| Code | Bedeutung |
|---|---|
| 0 | OK |
| 1 | Konfiguration, Zertifikat oder Verbindung fehlerhaft |
| 2 | Einzelne Zustellungen sind fehlgeschlagen (z. B. falsche Prüfsumme); sie werden beim nächsten Lauf erneut versucht |

## Testzugang des USP

Vor dem Echtbetrieb gegen den Testzugang prüfen (dasselbe Client-Zertifikat, eigener Ausgabeordner):

```properties
soap.endpoint=https://demo-autoabholung.meinpostkorb.brz.gv.at/soap
output.dir=C:/Postkorb/Test-Eingang
```

Mit `.\postkorb.cmd --config config\test.properties` o. ä. lässt sich eine eigene Konfiguration
für den Test verwenden. Laut How-To liefert der Testzugang bei „neuen Nachrichten“ immer dieselben 3 Nachrichten;
Abschließen und Löschen ändern die Testdaten nicht. Ein erfolgreicher Lauf legt also 3 Ordner an,
jeweils mit `mailbody.txt` und den PDF-Anhängen, und meldet `neu=3`. Jeder weitere Lauf meldet
`uebersprungen=3`.

## Ohne Zertifikat testen (Demo-Modus)

```properties
gateway=demo
demo.inbox=C:/Postkorb/demo-inbox
output.dir=C:/Postkorb/Eingang
```

Jeder Unterordner von `demo-inbox` gilt als Zustellung, jede Datei darin als Anhang.
Abgeschlossene Zustellungen werden nach `demo-inbox\.abgeschlossen` verschoben,
mit `delete.after.download=true` weiter nach `demo-inbox\.geloescht`.

## Aufbau

```
at.postkorb
├── Main                      Kommandozeile, Logging, Modi
├── PostkorbAbholer           Ablauf: abfragen → abrufen → speichern und prüfen → abschließen → optional löschen
├── config.Config             Properties + Umgebungsvariablen
├── tls.TlsContextFactory     Client-Zertifikat (PKCS12 / Windows-MY), BRZ-CA bzw. Truststore
├── gateway.PostkorbGateway   fachliche Schnittstelle
├── zuseaa.ZuseAaSoapGateway  SOAP-1.2-Client für die Automatische Abholung (JAXB aus zuseaa_p2.wsdl)
├── gateway.DemoGateway       lokaler Testordner
├── download.HttpAttachmentDownloader   REST-GET für Anhänge über mTLS
└── store.*                   Ablage, Dateinamen, Liste abgeholter IDs
```
