package at.postkorb.elak;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kommandozeilenbefehle zum Einrichten der ELAK-Anbindung:
 * <ul>
 *   <li>{@code --elak-pruefen}: meldet sich an und prüft nur lesend Mappentypen, Register und Workflows</li>
 *   <li>{@code --elak-testmappe <datei>}: legt genau eine Mappe (Typ Dokument) OHNE Workflow an</li>
 * </ul>
 */
public final class ElakWerkzeug {

    private ElakWerkzeug() {
    }

    public static int pruefen(Path configFile, PrintStream out) {
        try {
            ElakKonfiguration k = ElakKonfiguration.laden(configFile, true);
            try (ElakClient elak = new ElakClient(k.url(), Duration.ofSeconds(60))) {
                return pruefen(elak, k, out);
            }
        } catch (Exception e) {
            out.println("FEHLER: " + e.getMessage());
            return 1;
        }
    }

    static int pruefen(ElakClient elak, ElakKonfiguration k, PrintStream out) throws IOException {
        int probleme = 0;
        out.println("Server:  " + k.url());
        out.println("Mandant: " + k.mandant() + ", Benutzer: " + k.benutzer());
        elak.login(k.benutzer(), k.mandant(), k.passwort());
        out.println("[OK] Anmeldung");

        List<ElakClient.Eintrag> typen = elak.mappentypen();
        out.println("[OK] " + typen.size() + " Mappentypen sichtbar");
        for (String gesucht : List.of(k.mappentypDokument(), k.mappentypRechnung())) {
            Optional<ElakClient.Eintrag> t = finde(typen, gesucht);
            if (t.isEmpty()) {
                out.println("[!!] Mappentyp '" + gesucht + "' nicht gefunden. Vorhanden: " + namen(typen));
                probleme++;
                continue;
            }
            ElakClient.MappentypInfo info;
            try {
                info = elak.beschreibe(t.get().name(), t.get().id());
            } catch (IOException e) {
                out.println("[!!] Mappentyp '" + gesucht + "' vorhanden (id " + t.get().id() + "), Beschreibung fehlgeschlagen: "
                        + e.getMessage());
                probleme++;
                continue;
            }
            out.println("[OK] Mappentyp '" + t.get().name() + "' (id " + t.get().id() + ", Beschreibung mit categories='"
                    + elak.kategorien() + "')");
            out.println("       Register: " + info.register().stream().map(r -> r.name() + " (" + r.id() + ")").toList());
            out.println("       Felder:   " + info.felder());
            if (k.register() != null && info.register().stream().noneMatch(r -> k.register().equals(r.name()))) {
                out.println("[!!] Register '" + k.register() + "' gibt es in '" + info.name() + "' nicht");
                probleme++;
            }
        }

        List<ElakClient.Eintrag> wfs;
        try {
            wfs = elak.workflows();
        } catch (IOException e) {
            out.println("[!!] Workflows konnten nicht gelesen werden: " + e.getMessage());
            out.println(++probleme + " Problem(e) – nichts wurde verändert.");
            return 2;
        }
        out.println("[OK] " + wfs.size() + " Workflows sichtbar");
        for (String gesucht : List.of(k.workflowDokument(), k.workflowRechnung())) {
            Optional<ElakClient.Eintrag> w = finde(wfs, gesucht);
            if (w.isPresent()) {
                out.println("[OK] Workflow '" + w.get().name() + "' (id " + w.get().id() + ")");
            } else {
                out.println("[!!] Workflow '" + gesucht + "' nicht gefunden. Vorhanden: " + namen(wfs));
                probleme++;
            }
        }
        out.println(probleme == 0 ? "Alles in Ordnung – nichts wurde verändert." : probleme + " Problem(e) – nichts wurde verändert.");
        return probleme == 0 ? 0 : 2;
    }

    public static int testmappe(Path configFile, Path datei, PrintStream out) {
        try {
            ElakKonfiguration k = ElakKonfiguration.laden(configFile, true);
            try (ElakClient elak = new ElakClient(k.url(), Duration.ofSeconds(120))) {
                return testmappe(elak, k, datei, out);
            }
        } catch (Exception e) {
            out.println("FEHLER: " + e.getMessage());
            return 1;
        }
    }

    static int testmappe(ElakClient elak, ElakKonfiguration k, Path datei, PrintStream out) throws IOException {
        elak.login(k.benutzer(), k.mandant(), k.passwort());
        String register = registerFuer(elak, k, k.mappentypDokument());
        String id = elak.mappeAnlegen(k.mappentypDokument(), Map.of(),
                List.of(new ElakClient.Datei(datei.getFileName().toString(), register, Files.readAllBytes(datei))));
        out.println("[OK] Testmappe angelegt: Mappentyp '" + k.mappentypDokument() + "', Register '" + register
                + "', Mappen-ID " + id + " (ohne Workflow)");
        out.println("Bitte im ELAK prüfen und danach wieder löschen.");
        return 0;
    }

    /** Konfiguriertes Register oder das erste Register des Mappentyps. */
    public static String registerFuer(ElakClient elak, ElakKonfiguration k, String mappentyp) throws IOException {
        if (k.register() != null) {
            return k.register();
        }
        Optional<ElakClient.Eintrag> typ = finde(elak.mappentypen(), mappentyp);
        List<ElakClient.Eintrag> reg = elak.beschreibe(mappentyp, typ.map(ElakClient.Eintrag::id).orElse(null)).register();
        if (reg.isEmpty()) {
            throw new IOException("Mappentyp '" + mappentyp + "' hat keine Dokumentregister");
        }
        return reg.get(0).name();
    }

    static Optional<ElakClient.Eintrag> finde(List<ElakClient.Eintrag> liste, String name) {
        return liste.stream().filter(e -> name.equalsIgnoreCase(e.name())).findFirst();
    }

    private static List<String> namen(List<ElakClient.Eintrag> liste) {
        return liste.stream().map(ElakClient.Eintrag::name).toList();
    }
}
