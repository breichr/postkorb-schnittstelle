package at.postkorb.elak;

import java.io.Console;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * ELAK-Einstellungen aus postkorb.properties (Abschnitt "elak.").
 * Das Passwort kommt aus der Umgebungsvariable POSTKORB_ELAK_PASSWORD oder wird abgefragt.
 */
public record ElakKonfiguration(
        URI url,
        String benutzer,
        String mandant,
        char[] passwort,
        String mappentypDokument,
        String workflowDokument,
        String mappentypRechnung,
        String workflowRechnung,
        String register,
        String felderDokument,
        String felderRechnung) {

    public static final String DEFAULT_URL = "https://dce-ds-01.gemdatdce.at:12059";

    public static ElakKonfiguration laden(Path configFile, boolean passwortAbfragen) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        String benutzer = wert(p, "elak.benutzer", null);
        String mandant = wert(p, "elak.mandant", null);
        if (benutzer == null || mandant == null) {
            throw new IllegalArgumentException("elak.benutzer und elak.mandant in " + configFile + " eintragen");
        }
        char[] pw = null;
        String env = System.getenv("POSTKORB_ELAK_PASSWORD");
        if (env != null && !env.isEmpty()) {
            pw = env.toCharArray();
        } else if (passwortAbfragen) {
            Console c = System.console();
            if (c != null) {
                pw = c.readPassword("ELAK-Passwort für %s: ", benutzer);
            }
        }
        if (pw == null || pw.length == 0) {
            throw new IllegalArgumentException("ELAK-Passwort fehlt (Umgebungsvariable POSTKORB_ELAK_PASSWORD)");
        }
        return new ElakKonfiguration(
                URI.create(wert(p, "elak.url", DEFAULT_URL)),
                benutzer,
                mandant,
                pw,
                wert(p, "elak.dokument.mappentyp", "Dokument"),
                wert(p, "elak.dokument.workflow", "Dokument"),
                wert(p, "elak.rechnung.mappentyp", "Beleg"),
                wert(p, "elak.rechnung.workflow", "Outlook_ER"),
                wert(p, "elak.register", null),
                wert(p, "elak.dokument.felder", null),
                wert(p, "elak.rechnung.felder", null));
    }

    /** true, wenn in der Konfiguration ein ELAK-Benutzer eingetragen ist. */
    public static boolean konfiguriert(Path configFile) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return wert(p, "elak.benutzer", null) != null && !"false".equalsIgnoreCase(wert(p, "elak.aktiv", "true"));
    }

    private static String wert(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? def : v.strip();
    }
}
