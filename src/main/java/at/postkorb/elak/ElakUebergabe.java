package at.postkorb.elak;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Überträgt zugeordnete Anhänge aus der Warteliste in den ELAK: eine Mappe pro Datei, danach Workflow.
 * Die Mappen-ID wird sofort gespeichert – scheitert nur der Workflow-Start, wird beim nächsten
 * Versuch keine zweite Mappe angelegt.
 */
public final class ElakUebergabe {

    private static final Logger LOG = Logger.getLogger(ElakUebergabe.class.getName());

    public record Ergebnis(int uebergeben, int fehler, String ersterFehler) {
    }

    private final ElakKonfiguration k;
    private final Supplier<Elak> verbindung;

    public ElakUebergabe(ElakKonfiguration k, Supplier<Elak> verbindung) {
        this.k = k;
        this.verbindung = verbindung;
    }

    public Ergebnis uebergeben(Warteliste liste) throws IOException {
        List<Warteliste.Eintrag> offen = liste.wartenAufUebergabe();
        if (offen.isEmpty()) {
            return new Ergebnis(0, 0, null);
        }
        int ok = 0, err = 0;
        String erster = null;
        try (Elak elak = verbindung.get()) {
            elak.login(k.benutzer(), k.mandant(), k.passwort());
            Map<String, String> workflowIds = new HashMap<>();
            for (ElakClient.Eintrag w : elak.workflows()) {
                workflowIds.put(w.name().toLowerCase(), w.id());
            }
            Map<String, String> register = new HashMap<>();
            for (Warteliste.Eintrag e : offen) {
                for (Warteliste.Datei d : e.dateien()) {
                    if (!d.offeneUebergabe()) {
                        continue;
                    }
                    boolean rechnung = d.art() == Art.RECHNUNG;
                    String typ = rechnung ? k.mappentypRechnung() : k.mappentypDokument();
                    String wfName = rechnung ? k.workflowRechnung() : k.workflowDokument();
                    try {
                        String mappe = d.mappenId();
                        if (mappe == null) {
                            String reg = register.computeIfAbsent(typ, t -> registerFuer(elak, t));
                            byte[] inhalt = Files.readAllBytes(e.ordner().resolve(d.name()));
                            mappe = elak.mappeAnlegen(typ, felder(rechnung, e),
                                    List.of(new ElakClient.Datei(d.name(), reg, inhalt)));
                            liste.mappeAngelegt(e.id(), d.nr(), mappe);
                            String m = mappe;
                            LOG.info(() -> "ELAK: Mappe " + m + " (" + typ + ") für " + d.name() + " angelegt");
                        }
                        if (wfName != null && !wfName.isBlank()) {
                            String wfId = workflowIds.get(wfName.toLowerCase());
                            if (wfId == null) {
                                throw new IOException("Workflow '" + wfName + "' gibt es im ELAK nicht");
                            }
                            elak.workflowStarten(mappe, wfId);
                        }
                        liste.workflowGestartet(e.id(), d.nr());
                        ok++;
                    } catch (IOException | RuntimeException ex) {
                        err++;
                        String text = d.name() + ": " + ex.getMessage();
                        if (erster == null) {
                            erster = text;
                        }
                        LOG.log(Level.SEVERE, "ELAK-Übergabe fehlgeschlagen: " + text, ex);
                        liste.fehler(e.id(), d.nr(), ex.getMessage());
                    }
                }
            }
        }
        return new Ergebnis(ok, err, erster);
    }

    private String registerFuer(Elak elak, String typ) {
        if (k.register() != null) {
            return k.register();
        }
        try {
            String id = elak.mappentypen().stream().filter(t -> typ.equalsIgnoreCase(t.name()))
                    .map(ElakClient.Eintrag::id).findFirst().orElse(null);
            List<ElakClient.Eintrag> reg = elak.beschreibe(typ, id).register();
            if (reg.isEmpty()) {
                throw new IllegalStateException("Mappentyp '" + typ + "' hat keine Dokumentregister");
            }
            return reg.get(0).name();
        } catch (IOException e) {
            throw new IllegalStateException("Register für '" + typ + "' nicht ermittelbar: " + e.getMessage(), e);
        }
    }

    /** Feldwerte laut Konfiguration elak.dokument.felder / elak.rechnung.felder (Standard: keine, wie das Add-In). */
    Map<String, String> felder(boolean rechnung, Warteliste.Eintrag e) {
        String vorlage = rechnung ? k.felderRechnung() : k.felderDokument();
        Map<String, String> out = new LinkedHashMap<>();
        if (vorlage == null || vorlage.isBlank()) {
            return out;
        }
        for (String teil : vorlage.split(";")) {
            int eq = teil.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String wert = teil.substring(eq + 1).strip()
                    .replace("{betreff}", nz(e.betreff()))
                    .replace("{absender}", nz(e.absender()))
                    .replace("{gz}", nz(e.geschaeftszahl()))
                    .replace("{zustellqualitaet}", nz(e.zustellqualitaet()))
                    .replace("{eingang}", nz(e.eingang()))
                    .replace("{id}", nz(e.id()));
            if (!wert.isBlank()) {
                out.put(teil.substring(0, eq).strip(), wert);
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
