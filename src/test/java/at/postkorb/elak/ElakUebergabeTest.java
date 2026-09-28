package at.postkorb.elak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.postkorb.gateway.Zustellung;

class ElakUebergabeTest {

    @TempDir
    Path tmp;
    private Warteliste liste;
    private FakeElak elak;

    static final class FakeElak implements Elak {
        final List<String> aufrufe = new ArrayList<>();
        final List<Map<String, String>> felder = new ArrayList<>();
        int naechsteId = 1;
        boolean workflowFehler;
        boolean angemeldet;

        @Override
        public void login(String b, String m, char[] p) {
            angemeldet = true;
        }

        @Override
        public List<ElakClient.Eintrag> mappentypen() {
            return List.of(new ElakClient.Eintrag("ft_dok", "Dokument"), new ElakClient.Eintrag("ft_beleg", "Beleg"));
        }

        @Override
        public ElakClient.MappentypInfo beschreibe(String typ, String id) {
            return new ElakClient.MappentypInfo(id, typ, List.of(new ElakClient.Eintrag("r1", "Anlagen")), List.of());
        }

        @Override
        public List<ElakClient.Eintrag> workflows() {
            return List.of(new ElakClient.Eintrag("352:584544", "Dokument"), new ElakClient.Eintrag("352:25025", "Outlook_ER"));
        }

        @Override
        public String mappeAnlegen(String typ, Map<String, String> f, List<ElakClient.Datei> dateien) {
            String id = "M" + naechsteId++;
            aufrufe.add("createFile " + typ + " " + dateien.get(0).register() + " " + dateien.get(0).name() + " -> " + id);
            felder.add(f);
            return id;
        }

        @Override
        public void workflowStarten(String mappe, String wf) throws IOException {
            if (workflowFehler) {
                throw new ElakException("startWorkflow", 5, "Workflow gesperrt");
            }
            aufrufe.add("startWorkflow " + mappe + " " + wf);
        }

        @Override
        public void close() {
            aufrufe.add("logout");
        }
    }

    private ElakKonfiguration konfig(String felderDok) {
        return new ElakKonfiguration(URI.create("http://x"), "b", "m", "p".toCharArray(),
                "Dokument", "Dokument", "Beleg", "Outlook_ER", null, felderDok, null);
    }

    @BeforeEach
    void setUp() {
        liste = new Warteliste(tmp.resolve("Eingang"));
        elak = new FakeElak();
    }

    private Path zustellung(String id, String... dateien) throws IOException {
        Path ordner = Files.createDirectories(tmp.resolve("Eingang").resolve("2026-09-25_BH Zwettl_" + id));
        for (String d : dateien) {
            Files.writeString(ordner.resolve(d), "Inhalt " + d);
        }
        Files.writeString(ordner.resolve("zustellung.txt"), "ID: " + id);
        liste.hinzufuegen(new Zustellung(id, "BH Zwettl", "Kanalgebühr und Bescheid", Instant.parse("2026-09-25T08:00:00Z"),
                List.of(), Map.of("Geschäftszahl", "ZT-123/2026", "Zustellqualität", "RSa")), ordner);
        return ordner;
    }

    @Test
    void vorschlaege() {
        List<String> alle = List.of("mailbody", "Bescheid_Kanal.pdf", "Vorschreibung_Q3.pdf", "rechnung.xml");
        assertEquals(Art.KEINE, Vorschlag.fuer("mailbody", "x", alle));
        assertEquals(Art.DOKUMENT, Vorschlag.fuer("Bescheid_Kanal.pdf", "x", alle));
        assertEquals(Art.RECHNUNG, Vorschlag.fuer("Vorschreibung_Q3.pdf", "x", alle));
        assertEquals(Art.KEINE, Vorschlag.fuer("rechnung.xml", "x", alle));
        assertEquals(Art.RECHNUNG, Vorschlag.fuer("Scan123.pdf", "Ihre Rechnung Nr. 55", List.of("Scan123.pdf")));
        assertEquals(Art.RECHNUNG, Vorschlag.fuer("Beleg.pdf", "Neue Nachricht", List.of("Beleg.pdf", "ebInterface.xml")));
        assertEquals(Art.DOKUMENT, Vorschlag.fuer("Beschluss.pdf", "Beschluss", List.of("Beschluss.pdf")));
        assertEquals(Art.DOKUMENT, Vorschlag.fuer("Unbekannt.pdf", "Mitteilung", List.of("Unbekannt.pdf")));
    }

    @Test
    void wartelisteUeberlebtNeuladenUndIgnoriertZustellungTxt() throws IOException {
        zustellung("Z1", "mailbody.txt", "Bescheid.pdf");
        List<Warteliste.Eintrag> e = new Warteliste(tmp.resolve("Eingang")).wartenAufZuordnung();
        assertEquals(1, e.size());
        assertEquals(List.of("Bescheid.pdf", "mailbody.txt"), e.get(0).dateien().stream().map(Warteliste.Datei::name).toList());
        assertEquals("ZT-123/2026", e.get(0).geschaeftszahl());
        assertTrue(liste.wartenAufUebergabe().isEmpty());
    }

    @Test
    void eineMappeProPdfMitPassendemWorkflow() throws IOException {
        zustellung("Z1", "mailbody.txt", "Bescheid.pdf", "Vorschreibung.pdf");
        liste.zuordnen("Z1", Map.of("Bescheid.pdf", Art.DOKUMENT, "Vorschreibung.pdf", Art.RECHNUNG, "mailbody.txt", Art.KEINE));

        ElakUebergabe.Ergebnis r = new ElakUebergabe(konfig(null), () -> elak).uebergeben(liste);

        assertEquals(new ElakUebergabe.Ergebnis(2, 0, null), r);
        assertEquals(List.of(
                "createFile Dokument Anlagen Bescheid.pdf -> M1", "startWorkflow M1 352:584544",
                "createFile Beleg Anlagen Vorschreibung.pdf -> M2", "startWorkflow M2 352:25025",
                "logout"), elak.aufrufe);
        assertTrue(liste.alle().isEmpty(), "erledigte Einträge wandern nach .elak/erledigt");
        assertTrue(Files.exists(tmp.resolve("Eingang/.elak/erledigt/Z1.properties")));
        assertTrue(elak.felder.get(0).isEmpty(), "Standard wie Outlook-Add-In: keine Felder");
    }

    @Test
    void workflowFehlerErzeugtKeineZweiteMappe() throws IOException {
        zustellung("Z1", "Bescheid.pdf");
        liste.zuordnen("Z1", Map.of("Bescheid.pdf", Art.DOKUMENT));
        elak.workflowFehler = true;

        ElakUebergabe.Ergebnis r = new ElakUebergabe(konfig(null), () -> elak).uebergeben(liste);
        assertEquals(1, r.fehler());
        assertTrue(r.ersterFehler().contains("Workflow gesperrt"), r.ersterFehler());
        Warteliste.Datei d = liste.alle().get(0).dateien().get(0);
        assertEquals("M1", d.mappenId());
        assertFalse(d.workflowGestartet());

        elak.workflowFehler = false;
        elak.aufrufe.clear();
        assertEquals(1, new ElakUebergabe(konfig(null), () -> elak).uebergeben(liste).uebergeben());
        assertEquals(List.of("startWorkflow M1 352:584544", "logout"), elak.aufrufe);
        assertTrue(liste.alle().isEmpty());
    }

    @Test
    void nichtZugeordneteWerdenNichtUebergeben() throws IOException {
        zustellung("Z1", "Bescheid.pdf");
        assertEquals(new ElakUebergabe.Ergebnis(0, 0, null), new ElakUebergabe(konfig(null), () -> elak).uebergeben(liste));
        assertFalse(elak.angemeldet, "ohne Arbeit keine Anmeldung");
    }

    @Test
    void nurNichtUebernehmenIstSofortErledigt() throws IOException {
        zustellung("Z1", "mailbody.txt");
        liste.zuordnen("Z1", Map.of("mailbody.txt", Art.KEINE));
        assertTrue(liste.alle().isEmpty());
    }

    @Test
    void nachtragenAusZustellungTxt() throws IOException {
        Path ordner = Files.createDirectories(tmp.resolve("Eingang/2026-09-24_Bezirksgericht Zwettl_7a1f"));
        Files.writeString(ordner.resolve("Beschluss.pdf"), "%PDF");
        Files.writeString(ordner.resolve("mailbody.txt"), "Text");
        Files.writeString(ordner.resolve("zustellung.txt"), "ID: 7a1f\nAbsender: Bezirksgericht Zwettl\nBetreff: Beschluss\n"
                + "Eingang: 2026-09-24T08:00:00Z\nGeschäftszahl: 12 C 34/26\nZustellqualität: RSa\nAnhänge:\n  - Beschluss.pdf (application/pdf)\n");
        liste.nachtragen(ordner);
        Warteliste.Eintrag e = liste.wartenAufZuordnung().get(0);
        assertEquals("7a1f", e.id());
        assertEquals("Bezirksgericht Zwettl", e.absender());
        assertEquals("12 C 34/26", e.geschaeftszahl());
        assertEquals("RSa", e.zustellqualitaet());
        assertEquals(List.of("Beschluss.pdf", "mailbody.txt"), e.dateien().stream().map(Warteliste.Datei::name).toList());
    }

    @Test
    void felderLautKonfiguration() throws IOException {
        zustellung("Z1", "Bescheid.pdf");
        liste.zuordnen("Z1", Map.of("Bescheid.pdf", Art.DOKUMENT));
        new ElakUebergabe(konfig("BuchungstextBetreff={betreff}; Personenname={absender}; Fremdzahl={gz}; Leer={nix}"),
                () -> elak).uebergeben(liste);
        assertEquals(Map.of("BuchungstextBetreff", "Kanalgebühr und Bescheid", "Personenname", "BH Zwettl",
                "Fremdzahl", "ZT-123/2026", "Leer", "{nix}"), elak.felder.get(0));
        assertNull(elak.felder.get(0).get("Details"));
    }
}
