# postkorb-schnittstelle

Windows-Schnittstelle zum automatisierten Herunterladen von Dokumenten aus
**„Mein Postkorb“ im Unternehmensserviceportal (USP)**.

Die Schnittstelle nutzt die offizielle Funktion **„Automatische Abholung“** des USP:

- SOAP-Webservice unter `https://autoabholung.meinpostkorb.brz.gv.at/soap`
- beidseitig zertifikatsgesicherte Verbindung (Client-Zertifikat aus dem USP)
- jeder Anhang wird über einen eigenen REST-GET-Aufruf geladen (mit derselben TLS-Absicherung)
- Schnittstellenbeschreibung: `zuseaa_p2.wsdl` samt `.xsd`-Dateien, im USP als Maven-Projekt erhältlich

> ⚠️ **Rechtlicher Hinweis:** Eine abgeholte elektronische Zustellung gilt als zugestellt.
> Ab diesem Zeitpunkt laufen Fristen, z. B. für Beschwerden oder Zahlungen. Die heruntergeladenen
> Dokumente müssen also verlässlich bei den zuständigen Personen oder im ERP/DMS ankommen.

## Stand

| Teil | Status |
|---|---|
| Konfiguration, Client-Zertifikat (.p12/.pfx oder Windows-Zertifikatsspeicher), mTLS | ✅ |
| Ablage je Zustellung (Windows-taugliche Dateinamen, atomar, Metadaten) | ✅ |
| Keine doppelten Downloads, Bestätigung erst nach vollständigem Speichern | ✅ |
| Logging, Exit-Codes, Aufgabenplanung / Dauerbetrieb | ✅ |
| Demo-Modus ohne Zertifikat | ✅ |
| **SOAP-Anbindung (`ZuseAaSoapGateway`)** | ⏳ braucht die WSDL aus dem USP |

## Voraussetzungen im USP

1. Ein USP-Konto mit USP-Administrator.
2. Einem Benutzer ist die Rolle **„Postbevollmächtigter“** für „Mein Postkorb“ zugewiesen.
3. Der Postbevollmächtigte aktiviert unter **Mein Postkorb → Einstellungen → Automatische Abholung**
   die automatische Abholung.
4. Der USP-Administrator erzeugt dort das **Client-Zertifikat** und lädt es herunter,
   zusammen mit dem **Maven-Projekt mit WSDL/XSD**.

Anleitung des USP: [How-To Einrichtung der „Automatischen Abholung“](https://www.usp.gv.at/dam/jcr:0909b669-4372-438f-b3fd-c42a37ffc5f4/Mein_Postkorb_AutomatischeAbholung_HowTo.pdf)

## Bauen

Voraussetzung ist Java 17 oder neuer (z. B. [Eclipse Temurin](https://adoptium.net/)) und Maven.

```bat
mvn package
```

Das Ergebnis ist `target\postkorb-schnittstelle.jar`, eine lauffähige JAR mit allen Abhängigkeiten.

### SOAP-Anbindung aktivieren

1. `zuseaa_p2.wsdl` und alle `.xsd`-Dateien aus dem USP-Maven-Projekt nach `src/main/wsdl/` kopieren.
2. `mvn package` ausführen. Maven aktiviert dann automatisch das Profil `zuse-aa` und generiert den
   JAX-WS-Client nach `at.postkorb.zuseaa.generated`.
3. `at.postkorb.zuseaa.ZuseAaSoapGateway` implementiert `PostkorbGateway` mit den generierten Klassen
   und hat einen Konstruktor `(Config, SSLContext)`. Diese Klasse ist noch zu schreiben, weil sie die
   Operationen aus der WSDL kennen muss.

## Einrichten unter Windows

Beispiel für die Ordnerstruktur:

```
C:\Postkorb\
  postkorb-schnittstelle.jar
  postkorb.cmd                 (aus windows\)
  aufgabe-einrichten.ps1       (aus windows\)
  config\postkorb.properties   (aus config\postkorb.properties.example)
  zertifikat\client.p12
  runtime\                     (optional: mit jlink/jpackage gebündeltes Java)
```

1. Konfiguration anpassen: `config\postkorb.properties`. Das Zertifikats-Passwort am besten als
   Umgebungsvariable `POSTKORB_KEYSTORE_PASSWORD` setzen.
   Alternativ importiert man das Zertifikat in den Windows-Zertifikatsspeicher des Benutzers
   und setzt `tls.keystore.type=Windows-MY`.
2. Zertifikat prüfen:
   ```bat
   postkorb.cmd --check-tls
   ```
3. Einmal manuell abholen:
   ```bat
   postkorb.cmd
   ```
4. Regelmäßige Abholung einrichten (PowerShell):
   ```powershell
   .\aufgabe-einrichten.ps1 -Pfad C:\Postkorb -IntervallMinuten 60
   ```
   Alternativ läuft `postkorb.cmd --loop` dauerhaft, z. B. als Dienst über NSSM oder WinSW.

### Ablage

```
C:\Postkorb\Eingang\
  2026-09-25_Finanzamt Österreich_<Zustellungs-ID>\
    Bescheid.pdf
    zustellung.txt        ← Absender, Betreff, Eingang, Anhänge
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
| 2 | Einzelne Zustellungen sind fehlgeschlagen; sie werden beim nächsten Lauf erneut versucht |

## Ohne Zertifikat testen (Demo-Modus)

```properties
gateway=demo
demo.inbox=C:/Postkorb/demo-inbox
output.dir=C:/Postkorb/Eingang
```

Jeder Unterordner von `demo-inbox` gilt als Zustellung, jede Datei darin als Anhang.
Nach der Bestätigung werden die Unterordner nach `demo-inbox\.bestaetigt` verschoben.

## Aufbau

```
at.postkorb
├── Main                      Kommandozeile, Logging, Modi
├── PostkorbAbholer           Ablauf: abfragen → speichern → merken → bestätigen
├── config.Config             Properties + Umgebungsvariablen
├── tls.TlsContextFactory     Client-Zertifikat (PKCS12 / Windows-MY), Truststore
├── gateway.PostkorbGateway   fachliche Schnittstelle (SOAP-Implementierung folgt)
├── gateway.DemoGateway       lokaler Testordner
├── download.HttpAttachmentDownloader   REST-GET für Anhänge über mTLS
└── store.*                   Ablage, Dateinamen, Liste abgeholter IDs
```
