Hier die Dateien aus dem Maven-Projekt ablegen, das im USP unter
**Mein Postkorb → Einstellungen → Automatische Abholung** heruntergeladen werden kann:

- `zuseaa_p2.wsdl`
- alle referenzierten `.xsd`-Dateien (relative Pfade beibehalten)

Sobald `zuseaa_p2.wsdl` hier liegt, aktiviert Maven automatisch das Profil `zuse-aa`
und generiert den SOAP-Client nach `target/generated-sources`.
