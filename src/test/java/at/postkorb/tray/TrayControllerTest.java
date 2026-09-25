package at.postkorb.tray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.postkorb.PostkorbAbholer;
import at.postkorb.gateway.Anhang;
import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.gateway.Zustellung;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;
import at.postkorb.store.RunLock;
import at.postkorb.tray.TrayController.Zustand;

class TrayControllerTest {

    @TempDir
    Path tmp;

    private final List<String> meldungen = new ArrayList<>();
    private Zustand zustand;
    private String tooltip;
    private String status;
    private final Map<String, Zustellung> postkorb = new LinkedHashMap<>();
    private final List<String> abgeschlossen = new ArrayList<>();
    private Exception verbindungsfehler;
    private boolean gesperrt;
    private Instant zertifikatsAblauf;
    private MutableClock clock;
    private TrayController controller;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-25T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Vienna");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        TrayController.View view = new TrayController.View() {
            @Override
            public void zeige(Zustand z, String t, String s) {
                zustand = z;
                tooltip = t;
                status = s;
            }

            @Override
            public void meldung(String titel, String text, boolean fehler) {
                meldungen.add((fehler ? "FEHLER " : "") + titel + " | " + text);
            }
        };
        PostkorbGateway gateway = new PostkorbGateway() {
            @Override
            public List<String> neueZustellungen() {
                return postkorb.keySet().stream().filter(id -> !abgeschlossen.contains(id)).toList();
            }

            @Override
            public Zustellung abrufen(String id) {
                return postkorb.get(id);
            }

            @Override
            public InputStream oeffneAnhang(Zustellung z, Anhang a) {
                return new ByteArrayInputStream("%PDF".getBytes());
            }

            @Override
            public void abschliessen(String id) {
                abgeschlossen.add(id);
            }

            @Override
            public void loeschen(String id) {
            }
        };
        controller = new TrayController(view,
                beiNeu -> {
                    if (verbindungsfehler != null) {
                        throw verbindungsfehler;
                    }
                    return new PostkorbAbholer(gateway, new DocumentStore(tmp.resolve("out")),
                            new ProcessedStore(tmp.resolve("state.txt")), false, beiNeu);
                },
                () -> gesperrt ? null : RunLock.tryAcquire(tmp.resolve(".lock")),
                () -> zertifikatsAblauf,
                clock);
    }

    private void post(String id, String absender, String betreff, String qualitaet) {
        postkorb.put(id, new Zustellung(id, absender, betreff, Instant.parse("2026-09-24T08:00:00Z"),
                List.of(new Anhang("Beschluss.pdf", "application/pdf", URI.create("https://x/" + id))),
                qualitaet == null ? Map.of() : Map.of("Zustellqualität", qualitaet)));
    }

    @Test
    void ohnePostAllesOk() {
        controller.abholen();
        assertEquals(Zustand.OK, zustand);
        assertTrue(meldungen.isEmpty());
        assertTrue(status.endsWith("keine neue Post"), status);
    }

    @Test
    void neuePostWirdGemeldetBisEingangGeoeffnet() {
        post("Z1", "Bezirksgericht Zwettl", "Beschluss", "nonRSa");
        controller.abholen();

        assertEquals(Zustand.NEUE_POST, zustand);
        assertEquals(1, meldungen.size());
        assertTrue(meldungen.get(0).startsWith("Neue Zustellung im Postkorb | Bezirksgericht Zwettl: Beschluss"), meldungen.get(0));
        assertTrue(tooltip.contains("1 neue Zustellung(en)"), tooltip);

        controller.abholen(); // nichts Neues – bleibt blau, keine weitere Meldung
        assertEquals(Zustand.NEUE_POST, zustand);
        assertEquals(1, meldungen.size());

        controller.eingangGeoeffnet();
        assertEquals(Zustand.OK, zustand);
    }

    @Test
    void rsaWirdHervorgehoben() {
        post("Z1", "Finanzamt Österreich", "Bescheid", "nonRSa");
        post("Z2", "Bezirksgericht Zwettl", "Beschluss", "RSa");
        controller.abholen();

        assertEquals(1, meldungen.size());
        String m = meldungen.get(0);
        assertTrue(m.startsWith("Neue RSa-Zustellung – Frist beachten!"), m);
        assertTrue(m.contains("Bezirksgericht Zwettl: Beschluss [RSa]"), m);
        assertFalse(m.contains("Bescheid [nonRSa]"), m);
    }

    @Test
    void vieleNeueWerdenZusammengefasst() {
        for (int i = 1; i <= 5; i++) {
            post("Z" + i, "Absender " + i, "Betreff " + i, null);
        }
        controller.abholen();
        String m = meldungen.get(0);
        assertTrue(m.startsWith("5 neue Zustellungen im Postkorb"), m);
        assertTrue(m.contains("… und 2 weitere"), m);
    }

    @Test
    void fehlerWirdEinmalGemeldetUndVerschwindetWieder() {
        verbindungsfehler = new IOException("Verbindung fehlgeschlagen", new IOException("Connection refused"));
        controller.abholen();
        assertEquals(Zustand.FEHLER, zustand);
        assertEquals(1, meldungen.size());
        assertTrue(meldungen.get(0).startsWith("FEHLER USP Postkorb – Abholung fehlgeschlagen | Verbindung fehlgeschlagen (Connection refused)"),
                meldungen.get(0));

        controller.abholen(); // gleicher Fehler – nicht erneut melden
        assertEquals(1, meldungen.size());

        verbindungsfehler = null;
        controller.abholen();
        assertEquals(Zustand.OK, zustand);
    }

    @Test
    void fehlerHatVorrangVorNeuerPost() {
        post("Z1", "BH Zwettl", "Bescheid", null);
        controller.abholen();
        verbindungsfehler = new IOException("HTTP 503");
        controller.abholen();
        assertEquals(Zustand.FEHLER, zustand);
        verbindungsfehler = null;
        controller.abholen();
        assertEquals(Zustand.NEUE_POST, zustand); // ungelesene Post ist noch da
    }

    @Test
    void gesperrtWennAndereAbholungLaeuft() {
        post("Z1", "BH Zwettl", "Bescheid", null);
        gesperrt = true;
        controller.abholen();
        assertTrue(status.endsWith("übersprungen, andere Abholung läuft"), status);
        assertTrue(abgeschlossen.isEmpty());
        assertEquals(Zustand.OK, zustand);
    }

    @Test
    void warntVorAblaufDesZertifikatsHoechstensTaeglich() {
        zertifikatsAblauf = clock.now.plus(Duration.ofDays(10));
        controller.abholen();
        assertEquals(Zustand.WARNUNG, zustand);
        assertEquals(1, meldungen.size());
        assertTrue(meldungen.get(0).contains("läuft am 05.10.2026 ab (in 10 Tagen)"), meldungen.get(0));

        clock.now = clock.now.plus(Duration.ofHours(1));
        controller.abholen();
        assertEquals(1, meldungen.size());

        clock.now = clock.now.plus(Duration.ofHours(24));
        controller.abholen();
        assertEquals(2, meldungen.size());
    }

    @Test
    void abgelaufenesZertifikatIstFehler() {
        zertifikatsAblauf = clock.now.minus(Duration.ofDays(1));
        controller.abholen();
        assertEquals(Zustand.FEHLER, zustand);
        assertTrue(meldungen.get(0).startsWith("FEHLER USP Postkorb – Zertifikat"), meldungen.get(0));
        assertTrue(tooltip.contains("abgelaufen"), tooltip);
    }

    @Test
    void zertifikatMitGenugLaufzeitOhneWarnung() {
        zertifikatsAblauf = clock.now.plus(Duration.ofDays(200));
        controller.abholen();
        assertEquals(Zustand.OK, zustand);
        assertTrue(meldungen.isEmpty());
    }

    @Test
    void tooltipWirdAufWindowsGrenzeGekuerzt() {
        verbindungsfehler = new IOException("x".repeat(300));
        controller.abholen();
        assertTrue(tooltip.length() <= 127, "Länge " + tooltip.length());
    }

    @Test
    void runLockVerhindertParallelenLauf() throws IOException {
        Path f = tmp.resolve(".lock");
        try (RunLock a = RunLock.tryAcquire(f)) {
            assertNotNull(a);
            assertNull(RunLock.tryAcquire(f));
        }
        try (RunLock b = RunLock.tryAcquire(f)) {
            assertNotNull(b);
        }
    }

    @Test
    void symboleLassenSichZeichnen() {
        BufferedImage img = TrayIcons.zeichne(32, new Color(0xC62828));
        assertEquals(32, img.getWidth());
        assertEquals(new Color(0xC62828).getRGB(), img.getRGB(16, 2)); // Hintergrundfarbe
        Color unterkante = new Color(img.getRGB(16, 24), true); // Unterkante des Umschlags
        assertTrue(unterkante.getRed() > 200 && unterkante.getGreen() > 200 && unterkante.getBlue() > 200, unterkante.toString());
    }
}
